package de.jotomo.ruffyscripter;

import android.os.RemoteException;
import android.os.SystemClock;

import org.monkey.d.ruffy.ruffy.driver.IRTHandler;
import org.monkey.d.ruffy.ruffy.driver.IRuffyService;
import org.monkey.d.ruffy.ruffy.driver.display.Menu;
import org.monkey.d.ruffy.ruffy.driver.display.MenuAttribute;
import org.monkey.d.ruffy.ruffy.driver.display.MenuType;
import org.monkey.d.ruffy.ruffy.driver.display.menu.MenuTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jotomo.ruffyscripter.commands.Command;
import de.jotomo.ruffyscripter.commands.CommandException;
import de.jotomo.ruffyscripter.commands.CommandResult;
import de.jotomo.ruffyscripter.commands.PumpState;

// TODO regularly read "My data" history (boluses, TBR) to double check all commands ran successfully.
// Automatically compare against AAPS db, or log all requests in the PumpInterface (maybe Milos
// already logs those requests somewhere ... and verify they have all been ack'd by the pump properly

/**
 * provides scripting 'runtime' and operations. consider moving operations into a separate
 * class and inject that into executing commands, so that commands operately solely on
 * operations and are cleanly separated from the thread management, connection management etc
 */
public class RuffyScripter {
    private static final Logger log = LoggerFactory.getLogger(RuffyScripter.class);


    private final IRuffyService ruffyService;
    private final long connectionTimeOutMs = 5000;

    public volatile Menu currentMenu;
    private volatile long menuLastUpdated = 0;

    private volatile long lastCmdExecution;
    private volatile Command activeCmd = null;
    private volatile CommandResult cmdResult;


    public RuffyScripter(final IRuffyService ruffyService) {
        this.ruffyService = ruffyService;
        try {
            ruffyService.setHandler(mHandler);
            idleDisconnectMonitorThread.start();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private Thread idleDisconnectMonitorThread = new Thread(new Runnable() {
        @Override
        public void run() {
            SystemClock.sleep(10_000);
            while (true) {
                try {
                    if (ruffyService.isConnected() && activeCmd == null
                            && lastCmdExecution + connectionTimeOutMs < System.currentTimeMillis()) {
                        log.debug("Disconnecting after " + (connectionTimeOutMs / 1000) + "s inactivity timeout");
                        ruffyService.doRTDisconnect();
                        SystemClock.sleep(1000);
                    }
                } catch (RemoteException e) {
                    log.debug("Exception in idle disconnect monitor thread, carrying on", e);
                }
            }
        }
    }, "idle-disconnect-monitor");

    private IRTHandler mHandler = new IRTHandler.Stub() {
        @Override
        public void log(String message) throws RemoteException {
            log.trace(message);
        }

        @Override
        public void fail(String message) throws RemoteException {
            log.warn(message);
        }

        @Override
        public void requestBluetooth() throws RemoteException {
            log.trace("Ruffy invoked requestBluetooth callback");
        }

        @Override
        public void rtStopped() throws RemoteException {
            log.debug("rtStopped callback invoked");
            currentMenu = null;
        }

        @Override
        public void rtStarted() throws RemoteException {
            log.debug("rtStarted callback invoked");
        }

        @Override
        public void rtClearDisplay() throws RemoteException {
        }

        @Override
        public void rtUpdateDisplay(byte[] quarter, int which) throws RemoteException {
        }

        @Override
        public void rtDisplayHandleMenu(Menu menu) throws RemoteException {
            // method is called every ~500ms
            log.debug("rtDisplayHandleMenu: " + menu.getType());

            currentMenu = menu;
            menuLastUpdated = System.currentTimeMillis();

            // note that a WARNING_OR_ERROR menu can be a valid temporary state (cancelling TBR)
            // of a running command
            if (activeCmd == null && currentMenu.getType() == MenuType.WARNING_OR_ERROR) {
                log.warn("Warning/error menu encountered without a command running");
            }
        }

        @Override
        public void rtDisplayHandleNoMenu() throws RemoteException {
            log.debug("rtDisplayHandleNoMenu callback invoked");
        }

    };

    public boolean isPumpBusy() {
        return activeCmd != null;
    }

    // TODO still needed?
    // problem was some timing issue something when disconnectin from ruffy and immediately reconnecting
    private static class Returnable {
        CommandResult cmdResult;
    }

    public CommandResult runCommand(final Command cmd) {
        synchronized (RuffyScripter.class) {
            try {
                activeCmd = cmd;
                cmdResult = null;
                final RuffyScripter scripter = this;
                final Returnable returnable = new Returnable();
                Thread cmdThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ensureConnected();
                            cmdResult = null;
                            // wait till pump is ready for input
                            waitForMenuUpdate();
                            // check if pump is an an error state
                            if (currentMenu != null && currentMenu.getType() == MenuType.WARNING_OR_ERROR) {
                                try {
                                    PumpState pumpState = readPumpState();
                                    returnable.cmdResult = new CommandResult().message("Pump is in an error state: " + currentMenu.getAttribute(MenuAttribute.MESSAGE)).state(pumpState);
                                    return;
                                } catch (Exception e) {
                                    returnable.cmdResult = new CommandResult().message("Pump is in an error state, reading the error state resulted in the attached exception").exception(e);
                                    return;
                                }
                            }
                            log.debug("Cmd execution: connection ready, executing cmd " + cmd);
                            returnable.cmdResult = cmd.execute(scripter);
                        } catch (CommandException e) {
                            returnable.cmdResult = e.toCommandResult();
                        } catch (Exception e) {
                            returnable.cmdResult = new CommandResult().exception(e).message("Unexpected exception running cmd");
                        } finally {
                            lastCmdExecution = System.currentTimeMillis();
                        }
                    }
                }, cmd.toString());
                cmdThread.start();

                long timeout = System.currentTimeMillis() + 60 * 1000;
                while (cmdThread.isAlive()) {
                    log.trace("Waiting for running command to complete");
                    SystemClock.sleep(500);
                    if (System.currentTimeMillis() > timeout) {
                        log.error("Running command " + activeCmd + " timed out");
                        cmdThread.interrupt();
                        SystemClock.sleep(5000);
                        log.error("Thread dead yet? " + cmdThread.isAlive());
                        return new CommandResult().success(false).enacted(false).message("Command timed out");
                    }
                }

                if (returnable.cmdResult.state == null) {
                    returnable.cmdResult.state = readPumpState();
                }
                log.debug("Command result: " + returnable.cmdResult);
                return returnable.cmdResult;
            } catch (CommandException e) {
                return e.toCommandResult();
            } catch (Exception e) {
                return new CommandResult().exception(e).message("Unexpected exception communication with ruffy");
            } finally {
                activeCmd = null;
            }
        }
    }

    private void ensureConnected() {
        // did we get a menu update from the pump in the last 5s? Then we're connected
        if (currentMenu != null && menuLastUpdated + 5000 > System.currentTimeMillis()) {
            log.debug("Pump is sending us menu updating, so we're connected");
            return;
        }

        try {
            boolean connectInitSuccessful = ruffyService.doRTConnect() == 0;
            log.debug("Connect init successful: " + connectInitSuccessful);
            while (currentMenu == null) {
                log.debug("Waiting for first menu update to be sent");
                // waitForMenuUpdate times out after 90s and throws a CommandException
                waitForMenuUpdate();
            }
        } catch (RemoteException e) {
            throw new CommandException().exception(e).message("Unexpected exception while initiating/restoring pump connection");
        }
    }

    // below: methods to be used by commands
    // TODO move into a new Operations(scripter) class commands can delegate to
    // while refactoring, move everything thats not a command out of the commands package

    private static class Key {
        static byte NO_KEY = (byte) 0x00;
        static byte MENU = (byte) 0x03;
        static byte CHECK = (byte) 0x0C;
        static byte UP = (byte) 0x30;
        static byte DOWN = (byte) 0xC0;
    }

    public void pressUpKey() {
        log.debug("Pressing up key");
        pressKey(Key.UP);
        log.debug("Releasing up key");
    }

    public void pressDownKey() {
        log.debug("Pressing down key");
        pressKey(Key.DOWN);
        log.debug("Releasing down key");
    }

    public void pressCheckKey() {
        log.debug("Pressing check key");
        pressKey(Key.CHECK);
        log.debug("Releasing check key");
    }

    public void pressMenuKey() {
        log.debug("Pressing menu key");
        pressKey(Key.MENU);
        log.debug("Releasing menu key");
    }

    /**
     * Wait until the menu update is in
     */
    public void waitForMenuUpdate() {
        long timeoutExpired = System.currentTimeMillis() + 90 * 1000;
        long initialUpdateTime = menuLastUpdated;
        while (initialUpdateTime == menuLastUpdated) {
            if (System.currentTimeMillis() > timeoutExpired) {
                throw new CommandException().message("Timeout waiting for menu update");
            }
            SystemClock.sleep(50);
        }
    }

    private void pressKey(final byte key) {
        try {
            ruffyService.rtSendKey(key, true);
            SystemClock.sleep(100);
            ruffyService.rtSendKey(Key.NO_KEY, true);
        } catch (RemoteException e) {
            throw new CommandException().exception(e).message("Error while pressing buttons");
        }
    }

    public void navigateToMenu(MenuType desiredMenu) {
        MenuType startedFrom = currentMenu.getType();
        boolean movedOnce = false;
        while (currentMenu.getType() != desiredMenu) {
            MenuType currentMenuType = currentMenu.getType();
            if (movedOnce && currentMenuType == startedFrom) {
                throw new CommandException().message("Menu not found searching for " + desiredMenu
                        + ". Check menu settings on your pump to ensure it's not hidden.");
            }
            pressMenuKey();
            waitForMenuToBeLeft(currentMenuType);
            movedOnce = true;
        }
    }

    /**
     * Wait till a menu changed has completed, "away" from the menu provided as argument.
     */
    public void waitForMenuToBeLeft(MenuType menuType) {
        long timeout = System.currentTimeMillis() + 30 * 1000;
        while (currentMenu.getType() == menuType) {
            if (System.currentTimeMillis() > timeout) {
                throw new CommandException().message("Timeout waiting for menu " + menuType + " to be left");
            }
            SystemClock.sleep(10);
        }
    }

    public void verifyMenuIsDisplayed(MenuType expectedMenu) {
        verifyMenuIsDisplayed(expectedMenu, null);
    }

    public void verifyMenuIsDisplayed(MenuType expectedMenu, String failureMessage) {
        waitForMenuUpdate();
        int retries = 5;
        while (currentMenu.getType() != expectedMenu) {
            if (retries > 0) {
                SystemClock.sleep(200);
                retries = retries - 1;
            } else {
                if (failureMessage == null) {
                    failureMessage = "Invalid pump state, expected to be in menu " + expectedMenu + ", but current menu is " + currentMenu.getType();
                }
                throw new CommandException().message(failureMessage);
            }
        }
    }

    private PumpState readPumpState() {
        PumpState state = new PumpState();
        Menu menu = currentMenu;
        MenuType menuType = menu.getType();
        if (menuType == MenuType.MAIN_MENU) {
            Double tbrPercentage = (Double) menu.getAttribute(MenuAttribute.TBR);
            if (tbrPercentage != 100) {
                state.tbrActive = true;
                Double displayedTbr = (Double) menu.getAttribute(MenuAttribute.TBR);
                state.tbrPercent = displayedTbr.intValue();
                MenuTime durationMenuTime = ((MenuTime) menu.getAttribute(MenuAttribute.RUNTIME));
                state.tbrRemainingDuration = durationMenuTime.getHour() * 60 + durationMenuTime.getMinute();
                state.tbrRate = ((double) menu.getAttribute(MenuAttribute.BASAL_RATE));
            }
        } else if (menuType == MenuType.WARNING_OR_ERROR) {
            state.isErrorOrWarning = true;
            state.errorMsg = (String) menu.getAttribute(MenuAttribute.MESSAGE);
        } else {
            StringBuilder sb = new StringBuilder();
            for (MenuAttribute menuAttribute : menu.attributes()) {
                sb.append(menuAttribute);
                sb.append(": ");
                sb.append(menu.getAttribute(menuAttribute));
                sb.append("\n");
            }
            state.errorMsg = "Pump is on menu " + menuType + ", listing attributes: \n" + sb.toString();
        }
        return state;
    }
}