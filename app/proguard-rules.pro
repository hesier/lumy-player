# kotlinx.serialization：保留生成的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hesier.**$$serializer { *; }

# Ak creates ECS systems through Class.getConstructor(DanmakuContext).
-keepclassmembers class * extends com.kuaishou.akdanmaku.ecs.base.DanmakuEntitySystem {
    public <init>(com.kuaishou.akdanmaku.ecs.DanmakuContext);
}

# Ashley creates pooled components through their no-argument constructors.
-keepclassmembers class * extends com.kuaishou.akdanmaku.ecs.base.DanmakuBaseComponent {
    public <init>();
}
