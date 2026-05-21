-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract <methods>;
}
-keepclassmembers class com.delivery.app.data.model.** { *; }
-keepclassmembers class com.delivery.app.data.local.** { *; }
-keepclassmembers class com.delivery.app.data.remote.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
