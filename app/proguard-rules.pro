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

# OpenAI's character renderer (read from the ChatGPT app at run time, rear/dots) binds its native functions by
# this class's exact name and signatures, and calls the dots service's binder by its AIDL.
-keep class com.openai.orbit.characters.NativeBridge { *; }
-keep class dev.kb896.aiusage.IDots** { *; }

# The guard (rear/RearGuardService) is the second Shizuku user service, started by its class name exactly as
# RearUserService is (ShizukuBridge.guardArgs). Nothing in the app calls its constructor, so without this R8
# removed it and gave its name to other classes: a release build would start no guard, and the back would have
# no cover while the main shell is away.
-keep class dev.kb896.aiusage.rear.RearGuardService { *; }
-keep interface dev.kb896.aiusage.IRearGuard { *; }
-keep class dev.kb896.aiusage.IRearGuard$Stub { *; }
