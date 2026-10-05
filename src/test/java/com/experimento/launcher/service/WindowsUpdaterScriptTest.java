package com.experimento.launcher.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El script de actualización de Windows es lo único que puede pasar el código de salida real
 * del instalador: el proceso se lanza desacoplado y el launcher muere antes de que termine.
 *
 * <p>Estos tests leen el script tal cual se escribe en disco; no ejecutan Windows.
 */
class WindowsUpdaterScriptTest {

    private static String script() throws Exception {
        Method m = AutoUpdateService.class
                .getDeclaredMethod("windowsUpdaterScript", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, "C:\\Users\\j\\updates\\meacore-update-1.exe",
                "C:\\Users\\j\\install\\MeaCore Launcher.exe");
    }

    @Test
    void waitsForTheInstallerAndKeepsItsPid() throws Exception {
        String bat = script();

        // /wait es lo que hace que ERRORLEVEL sea el del instalador, no el del arranque.
        assertTrue(bat.contains("/wait"), bat);
        assertTrue(bat.contains("INSTALLER_PID=%ERRORLEVEL%"),
                "hay que guardar el exit code del instalador: " + bat);
        assertTrue(bat.contains("echo PID del instalador: %INSTALLER_PID%"), bat);
    }

    @Test
    void relaunchesOnlyWhenTheInstallerSucceeded() throws Exception {
        String bat = script();

        assertTrue(bat.contains("if not \"%INSTALLER_PID%\"==\"0\""),
                "el relanzamiento debe estar condicionado al exit code: " + bat);
        // La rama de éxito (fuera del if) es la que relanza.
        int guard = bat.indexOf("if not \"%INSTALLER_PID%\"==\"0\"");
        int relaunch = bat.indexOf("start \"\"", guard);
        int closingBrace = bat.indexOf(")\r\n", guard);
        assertTrue(relaunch > closingBrace,
                "el relanzamiento debe quedar FUERA de la rama de error: " + bat);
    }

    @Test
    void failureIsRecordedAndNotSilent() throws Exception {
        String bat = script();

        assertTrue(bat.contains("meacore_update.log"),
                "un fallo debe dejar rastro: " + bat);
        assertTrue(bat.contains("Instalacion fallida"), bat);
        assertTrue(bat.contains("NO se ha relanzado"), bat);
    }

    /** Inno Setup devuelve 0 en éxito y distinto de 0 si falla o el usuario cancela. */
    @Test
    void treatsOnlyZeroAsSuccess() throws Exception {
        String bat = script();
        assertTrue(bat.contains("==\"0\""), bat);
        assertFalse(bat.contains(">=0"), "cualquier código distinto de 0 es fallo: " + bat);
    }

    @Test
    void killsTheLauncherBeforeInstalling() throws Exception {
        String bat = script();

        int kill = bat.indexOf("taskkill");
        int install = bat.indexOf("start \"\" /wait");
        assertTrue(kill > 0 && kill < install,
                "el launcher se cierra antes de instalar: " + bat);
    }

    /** Sin PowerShell ni saltarse la protección de SmartScreen. */
    @Test
    void doesNotInvokePowerShellOrUnblock() throws Exception {
        String bat = script();

        assertFalse(bat.toLowerCase().contains("powershell"), bat);
        assertFalse(bat.toLowerCase().contains("unblock"), bat);
        assertFalse(bat.contains("ExecutionPolicy"), bat);
    }

    /** Si el ejecutable del launcher no está donde se esperaba, se avisa en vez de fallar en silencio. */
    @Test
    void missingLauncherIsReported() throws Exception {
        String bat = script();

        assertTrue(bat.contains("if exist"), bat);
        assertTrue(bat.contains("No se encontro el launcher"), bat);
    }

    @Test
    void scriptCleansItselfUp() throws Exception {
        assertTrue(script().contains("del \"%~f0\""), "el .bat debe borrarse solo");
    }
}