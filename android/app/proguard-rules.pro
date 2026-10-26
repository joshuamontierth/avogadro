# The ViewModel is created reflectively from its (Application) constructor.
-keepclassmembers class net.montierth.avogadro.ui.SearchViewModel { <init>(android.app.Application); }

# OkHttp optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
