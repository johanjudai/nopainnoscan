# Retrofit + Gson : garder les DTO (réflexion sur les noms de champs) et les génériques.
-keep class com.maitre.nopainnoscan.api.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
# Réponse GitHub Releases désérialisée par Gson.
-keep class com.maitre.nopainnoscan.update.AppUpdater$Release { *; }
-keep class com.maitre.nopainnoscan.update.AppUpdater$Asset { *; }
