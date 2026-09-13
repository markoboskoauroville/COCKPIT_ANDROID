# The Shizuku user service is created by Shizuku's server by name and through reflection, so R8
# must keep its class, its constructors and its Stub. Everything else in this app is reached from
# the manifest or from code, and needs no rule (delivery-gate.md 6.3: a -keep rule with no reason
# is dead code carrying a note saying spare this).
-keep class com.mantra.cockpit.ShellService { *; }
-keep class com.mantra.cockpit.IShell { *; }
-keep class com.mantra.cockpit.IShell$Stub { *; }
