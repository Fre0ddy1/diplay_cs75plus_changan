# CS75PLUS应用品牌与发行维护

应用品牌集中在`mobile/src/main/res/values/strings_cs75plus.xml`，使用独立、不可翻译的`cs75plus_app_name`资源，避免上游各语言`mobile_app_name`覆盖名称。mobile主Manifest只保留一个资源引用，debug Manifest继承该名称。

`scripts/check_customization_integration.py`检查品牌资源、主Manifest和各variant的覆盖值。合并上游时，与HUD／仪表通用接入点一并检查。

发行版本在`mobile/build.gradle.kts`维护。46版沿用45版的未混淆standalone debug构建方式及诊断能力，去除旧`-hud-test`版本名称后缀；没有切换到尚未实车验证的压缩构建路径。包名及最终车机白名单签名保持一致，独立显示名不改变应用数据目录或配对身份。

发布流程：完成源码检查、定向回归、standalone构建、lint、品牌／版本／证书／运行资产核验后，在定制分支提交；新发行标签指向该提交。APK与校验文件放GitHub Release，原始APK、认证材料、签名输入、构建缓存及车机日志不提交Git。master继续保存上游原始历史。

发行说明应区分45版既有实车验证与46版的元数据重编译，保留原厂巡航竞争、ADB干扰投屏和长期重连稳定性等实际边界。未经实测的音频参数不并入发行改动。
