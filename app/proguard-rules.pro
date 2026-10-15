-keep class fi.iki.elonen.NanoHTTPD$* { *; }
-keep class com.ngrok.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

-keep class dev.pocket.mcp.ShellDisplayService { *; }
-keep class dev.pocket.mcp.IDisplayShell** { *; }
