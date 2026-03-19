package com.xiaobai.livephotoutil.compat

/**
 * Manifest 签名校验策略。
 */
enum class ManifestSignaturePolicy {
    /** 签名可选：即使配置了密钥，遇到无签名 manifest 也允许通过。 */
    OPTIONAL,

    /** 默认安全策略：当配置了任意签名/验签密钥时，manifest 必须带签名。 */
    REQUIRE_WHEN_KEY_CONFIGURED,

    /** 强制签名：无论是否配置密钥，manifest 都必须带签名。 */
    REQUIRED
}
