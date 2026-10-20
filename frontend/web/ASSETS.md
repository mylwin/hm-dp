# 图片与图标来源

## 图标

所有界面图标均来自已安装的 `@phosphor-icons/react`，统一 regular 线条风格。
品牌标识使用其 Storefront 图标，评分星星等实心表达使用同一库的 fill 变体。
没有 emoji 图标或手绘 SVG 路径。
`public/favicon.svg` 也由同一组件库的 Storefront 组件服务端渲染导出。

## 原项目图片

`public/imgs/` 复制自 `../legacy/imgs/`，用于兼容原接口中
`/imgs/...` 图片路径。探店文字与图片属于原项目示例资料，可能包含用户原文表情。

`public/images/lifestyle-*.webp` 为原项目照片转换的 WebP 版本。
`public/images/blog-*.webp` 对应原探店封面的 WebP 版本；`-small` 为 400px 缩略图。
只在示例商家快照与静态内容中使用，不代表与某家商家的真实对应关系。
实时商家相册直接使用后端 `images` 字段；加载失败展示中性占位，不替换为其他店铺照片。

## 生成主视觉

- 工具：内置 image_gen，2026-10-17。
- 文件：`public/images/dining-hero.webp`，转换为宽 1600px 的 WebP。
- 用途：首页本地生活主题摄影；不是具体商家或优惠活动的事实展示。
- 原始图片保留在 Codex generated_images 目录，项目发布使用本地 WebP 副本。

生成提示词：

> Use case: photorealistic-natural. Create one high-quality wide 16:9 editorial
> lifestyle photograph for the hero of a Chinese local restaurant discovery
> desktop website. A beautiful contemporary Hangzhou restaurant table by a
> window: steamed dim sum in bamboo steamer, glossy red braised meat in a dark
> ceramic plate, tea cups, one small plate of spring onion garnish, fresh green
> plant in softly blurred background. Warm natural afternoon side light,
> realistic food textures, earthy terracotta tabletop and rich forest-green
> seating in background. Camera at 35 degree overhead angle, close stylish
> restaurant photography, rich yet natural colors, no people, no hands,
> no text, no letters, no logos, no UI, no icons, no collage. Food composition
> on right two-thirds, darker clean left-third negative space for HTML overlay
> text. Visually inviting, upscale but everyday attainable local dining.
