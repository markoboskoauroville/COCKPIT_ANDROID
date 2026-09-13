// The one call the Shizuku user service offers: run a command as the shell user and return what
// it printed. The process runs with Shizuku's rights (shell, or root under Sui), outside this app.
package com.mantra.cockpit;

interface IShell {
    void destroy() = 16777114; // the destroy method Shizuku's server calls
    void exit() = 1;
    String run(String command, int timeoutMs) = 2;
}
