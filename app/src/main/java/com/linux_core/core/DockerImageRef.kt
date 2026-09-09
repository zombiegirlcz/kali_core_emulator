package com.linux_core.core

/**
 * Parsed container image reference (Docker Hub + custom registries).
 *
 * Supported formats:
 *   - alpine                          → namespace=library, repo=alpine, tag=latest
 *   - kali/security                   → namespace=kali, repo=security, tag=latest
 *   - myuser/app:v1.2.3               → namespace=myuser, repo=app, tag=v1.2.3
 *   - alpine@sha256:abcdef            → digest pull
 *   - 61b65dc6…(64 hex)              → bare digest → library/alpine@sha256:…
 *   - docker.io/ubuntu                → registry host stripped correctly
 *   - index.docker.io/library/ubuntu  → ditto
 *   - ghcr.io/owner/image[:tag|@digest] → custom registry host
 *   - quay.io/owner/image             → custom registry host
 *   - registry.example.com:5000/a/b   → custom registry with port
 */
data class DockerImageRef(
    val namespace: String,
    val repository: String,
    val tag: String,
    val digest: String,
    val registryHost: String = DEFAULT_REGISTRY_HOST,
    val isHttp: Boolean = false
) {
    companion object {
        const val DEFAULT_REGISTRY_HOST = "registry-1.docker.io"
        private const val DOCKER_AUTH_HOST = "auth.docker.io"
        private const val DOCKER_SERVICE = "registry.docker.io"

        /** Známé aliasy Docker Hubu — stripnou se na čisté name/repo. */
        private val DOCKER_HUB_ALIASES = setOf(
            "docker.io", "index.docker.io", "registry.hub.docker.com", "hub.docker.com"
        )

        /** Custom registry hosts s vlastním auth serverem. */
        private val KNOWN_REGISTRIES = mapOf(
            "ghcr.io" to "https://ghcr.io/token",           // GitHub Container Registry (token endpoint)
            "quay.io" to "https://quay.io/v2/auth",          // Red Hat Quay
            "registry.gitlab.com" to "https://gitlab.com/jwt/auth",
            "lscr.io" to "https://lscr.io/token",            // LinuxServer.io
        )

        fun parse(input: String): DockerImageRef {
            var trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "Image reference cannot be empty" }

            var isHttp = false
            if (trimmed.startsWith("http://", ignoreCase = true)) {
                isHttp = true
                trimmed = trimmed.substring("http://".length)
            } else if (trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed = trimmed.substring("https://".length)
            }

            val lower = trimmed.lowercase()
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz") || lower.endsWith(".tgz") || lower.endsWith(".txz")) {
                throw IllegalArgumentException("Web archive URLs should be pulled via URL pull: $input")
            }

            require(!trimmed.startsWith("/")) { "Image reference cannot start with /" }
            require(!trimmed.startsWith(":")) { "Image reference cannot start with :" }

            // ── 1. Odděl registry host (pokud je uveden) ──
            var registryHost = DEFAULT_REGISTRY_HOST
            val firstSlash = trimmed.indexOf('/')
            if (firstSlash > 0) {
                val candidate = trimmed.substring(0, firstSlash)
                val looksLikeHost = candidate.contains('.') || candidate.contains(':') ||
                    KNOWN_REGISTRIES.containsKey(candidate)
                if (looksLikeHost && !candidate.startsWith("sha256")) {
                    val hostNoPort = candidate.substringBefore(':')
                    when {
                        hostNoPort in DOCKER_HUB_ALIASES -> {
                            registryHost = DEFAULT_REGISTRY_HOST
                        }
                        else -> registryHost = candidate
                    }
                    trimmed = trimmed.substring(firstSlash + 1)
                }
            }

            // ── 2. Bare digest (64 hex znaků bez @) → library/alpine-style fallback nejde
            //      rozeznat od repa — ale 64 hex znaků není platné repo jméno (velká písmena/
            //      délka), takže ho interpretujeme jako digest pro library/ namespace jen když
            //      uživatel zadal i repo: `ubuntu 61b65…` nezvládneme, `ubuntu@61b65…` ano.
            //      Zde: celý vstup = hex → chyba s jasnou hláškou.
            if (Regex("^[0-9a-fA-F]{64}$").matches(trimmed)) {
                throw IllegalArgumentException(
                    "Bare digest without image name — use format: ubuntu@$trimmed"
                )
            }

            // ── 3. Odděl @digest ──
            var namePart = trimmed
            var digestPart = ""
            var tagPart = ""
            val atIndex = trimmed.lastIndexOf('@')
            if (atIndex >= 0) {
                namePart = trimmed.substring(0, atIndex)
                digestPart = trimmed.substring(atIndex + 1)
                // Přijmout i bare hex za @ → normalizovat na sha256:
                if (!digestPart.startsWith("sha256:")) {
                    require(Regex("^[0-9a-fA-F]{64}$").matches(digestPart)) {
                        "Only sha256 digests are supported (got: $digestPart)"
                    }
                    digestPart = "sha256:$digestPart"
                }
            } else {
                // Tag (poslední dvojtečka, ale NE u portu registru — ten je už oddělený)
                val colonIndex = namePart.lastIndexOf(':')
                if (colonIndex >= 0) {
                    val afterColon = namePart.substring(colonIndex + 1)
                    require(afterColon.isNotEmpty() && !afterColon.contains('/')) {
                        "Invalid tag format: $input"
                    }
                    tagPart = afterColon
                    namePart = namePart.substring(0, colonIndex)
                } else {
                    tagPart = "latest"
                }
            }

            // ── 4. Namespace/repository split ──
            val slashIndex = namePart.indexOf('/')
            val (namespace, repository) = if (slashIndex >= 0) {
                namePart.substring(0, slashIndex) to namePart.substring(slashIndex + 1)
            } else {
                if (registryHost == DEFAULT_REGISTRY_HOST) "library" to namePart
                else throw IllegalArgumentException(
                    "Custom registry $registryHost requires namespace/repo format: $registryHost/<ns>/<repo>"
                )
            }

            require(namespace.isNotEmpty()) { "Namespace cannot be empty" }
            require(repository.isNotEmpty() && !repository.contains('/') && !repository.contains(':')) {
                "Repository name cannot be empty, nested, or contain colons: '$repository'"
            }

            return DockerImageRef(
                namespace = namespace,
                repository = repository,
                tag = tagPart,
                digest = digestPart,
                registryHost = registryHost,
                isHttp = isHttp
            )
        }
    }

    val hasDigest: Boolean
        get() = digest.isNotEmpty()

    val fullName: String
        get() = if (namespace == "library" && registryHost == DEFAULT_REGISTRY_HOST) {
            repository
        } else {
            "$namespace/$repository"
        }

    val scheme: String
        get() = if (isHttp) "http" else "https"

    /** Host auth serveru pro OAuth2 token exchange. */
    val authRealm: String
        get() = when {
            registryHost == DEFAULT_REGISTRY_HOST ||
            registryHost in DOCKER_HUB_ALIASES -> "$DOCKER_AUTH_HOST/token"
            KNOWN_REGISTRIES.containsKey(registryHost) ->
                KNOWN_REGISTRIES[registryHost]!!.removePrefix("https://").removePrefix("http://")
            else -> "$registryHost/token"
        }

    /** Service parametr pro token endpoint. */
    val authService: String
        get() = when {
            registryHost == DEFAULT_REGISTRY_HOST ||
            registryHost in DOCKER_HUB_ALIASES -> DOCKER_SERVICE
            registryHost == "quay.io" -> "quay.io"
            else -> registryHost
        }

    val authScope: String
        get() = "repository:$fullName:pull"

    fun manifestUrl(acceptHeader: String = "application/v2+json"): String {
        val ref = if (hasDigest) {
            if (digest.startsWith("sha256:")) digest else "sha256:$digest"
        } else tag
        return "$scheme://$registryHost/v2/$fullName/manifests/$ref"
    }

    fun blobUrl(digestValue: String): String {
        return "$scheme://$registryHost/v2/$fullName/blobs/$digestValue"
    }
}
