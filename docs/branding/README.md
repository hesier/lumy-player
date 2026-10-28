# Lumy 应用标识

`lumy.svg` 是图标与电视横幅的源稿，使用深灰背景 `#1E1F24`、纯色双层播放图形和定制的 Nunito 字标。

- `ic_lumy_mark.xml` 用两条等大的矢量路径绘制图案，前层不透明度为 90%，不使用 PNG、渐变或模糊滤镜；Android 5.0 起使用相同效果。
- 图形按 108 网格绘制，图标固有尺寸为 48dp；`ic_lumy_mark_adaptive.xml` 按前景层的 9.26%（108 网格的 10）添加内边距，避免桌面圆形裁切截断主体。
- `ic_lumy_wordmark.xml` 保留矢量字形，加粗描边也已转换为填充路径，包含 L / Y 加宽及 Y 交汇处下移的调整。
- `banner.xml` 组合图形与字标；`ic_launcher.xml` 提供旧版桌面和应用内使用的圆角背景。

修改源稿后需同步修改对应矢量 XML；仅修改 SVG 不会自动更新 APK。使用 `./gradlew :app:assembleDebug :app:lintDebug` 检查打包与资源兼容性。
