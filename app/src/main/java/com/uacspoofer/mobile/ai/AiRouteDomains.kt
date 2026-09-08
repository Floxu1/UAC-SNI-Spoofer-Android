package com.uacspoofer.mobile.ai

/**
 * Domains that reject datacenter exit addresses and therefore need the clean AI hop.
 * These are literal rules instead of `geosite:` categories because the app never
 * publishes `XRAY_LOCATION_ASSET`, so a geosite lookup would fail the whole config.
 */
object AiRouteDomains {
    val RULES: List<String> = listOf(
        // OpenAI / ChatGPT / Sora
        "domain:openai.com",
        "domain:chatgpt.com",
        "domain:oaistatic.com",
        "domain:oaiusercontent.com",
        "domain:sora.com",
        "domain:openai.org",
        // Anthropic
        "domain:claude.ai",
        "domain:anthropic.com",
        // Google Gemini and AI Studio, scoped to the AI hosts only so the rest of
        // Google keeps using the normal tunnel.
        "full:gemini.google.com",
        "full:aistudio.google.com",
        "full:makersuite.google.com",
        "full:bard.google.com",
        "full:generativelanguage.googleapis.com",
        "full:alkalimakersuite-pa.clients6.google.com",
        "full:proactivebackend-pa.googleapis.com",
        // Other assistants that apply the same datacenter policy
        "domain:perplexity.ai",
        "domain:x.ai",
        "domain:grok.com",
        "domain:mistral.ai",
        "domain:deepseek.com",
        "domain:poe.com",
        "domain:meta.ai",
        "full:copilot.microsoft.com",
        "domain:githubcopilot.com",
    )

    /** Hosts used to decide whether the clean hop is needed at all. */
    val REACHABILITY_PROBES: List<String> = listOf(
        "https://chatgpt.com/cdn-cgi/trace",
        "https://gemini.google.com/robots.txt",
    )
}
