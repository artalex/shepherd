# JSch creates its ciphers, key exchanges and signatures by class name from its config.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch integrations that Android lacks and the app doesn't use: Pageant (JNA), log4j2 and slf4j
# loggers, Kerberos (GSSAPI), junixsocket.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
