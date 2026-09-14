# 测试密钥目录

本目录**不再存放任何 PEM 密钥文件**。

`RsaKeyLoaderTest` 在测试运行时用 `KeyPairGenerator` 生成一次性 RSA 密钥对，
再以 PEM 字符串 / 临时文件两种方式喂给 `RsaKeyLoader`，因此：

- 仓库里不存在私钥，不会被 GitHub secret scanning / push protection 拦截；
- 测试仍然覆盖 PKCS#8 私钥解析与 X.509 公钥解析两条路径。

生产密钥通过环境变量注入（`JWT_RSA_PRIVATE_KEY` / `JWT_RSA_PUBLIC_KEY`）
或文件路径注入（`JWT_RSA_PRIVATE_KEY_PATH` / `JWT_RSA_PUBLIC_KEY_PATH`），
参见 `config/keys/README.md`。
