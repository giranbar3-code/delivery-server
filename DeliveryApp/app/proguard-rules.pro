-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract <methods>;
}
-keep class com.delivery.app.data.model.** { *; }
-keep class com.delivery.app.data.local.** { *; }
-keep class com.delivery.app.data.remote.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
