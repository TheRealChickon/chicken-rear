# R8 strips the reflectively-constructed Shizuku user service otherwise, and
# the failure is a runtime InstantiationException that will not show up in a
# debug build.
-keep class dev.kb896.aiusage.rear.RearUserService { *; }
-keep interface dev.kb896.aiusage.IRearService { *; }
-keep class dev.kb896.aiusage.IRearService$Stub { *; }

# The guard (a second Shizuku user service, started by class name like RearUserService): without
# these R8 empties it, since nothing in the app calls its constructor.
-keep class dev.kb896.aiusage.rear.RearGuardService { *; }
-keep interface dev.kb896.aiusage.IRearGuard { *; }
-keep class dev.kb896.aiusage.IRearGuard$Stub { *; }
