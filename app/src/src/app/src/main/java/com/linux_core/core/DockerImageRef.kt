package com.linux_core.core

/**
 * Parsed Docker image reference.
 *
 * Supported formats:
 *   - alpine                          → namespace=library, repo=alpine, tag=latest
 *   - kali/security                   → namespace=kali, repo=security, tag=latest
 *   - myuser/app:v1.2.3              → namespace=myuser, repo=app, tag=v1.2.3
 *   - alpine@sha256:abcdef           → namespace=library, repo=alpine, digest=sha256:abcdef
 */
data class DockerImageRef(
    val namespace: String,
    val repository: String,
    val tag: String,
    val digest: String
) {
    val hasDigest: Boolean
        get() = digest.isNotEmpty()

    val fullName: String
        get() = if (namespace == "library") {
            repository
        } else {
            "$namespace/$repository"
        }

    val registryHost: String
        get() = "registry-1.docker.io"

    val authScope: String
        get() = "repository:$fullName:pull"

    fun manifestUrl(acceptHeader: String = "application/v2+json"): String {
        val ref = if (hasDigest) "sha256:${digest.substringAfter("sha256:")}" else tag
        return "https://$registryHost/v2/$fullName/manifests/$ref"
    }

    fun blobUrl(digestValue: String): String {
        return "https://$registryHost/v2/$fullName/blobs/$digestValue"
    }

    companion object {
        fun parse(input: String): DockerImageRef {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "Image reference cannot be empty" }
            require(!trimmed.startsWith("/")) { "Image reference cannot start with /" }
            require(!trimmed.startsWith(":")) { "Image reference cannot start with :" }

            val (namePart, tagPart, digestPart) = parseReference(trimmed)
            require(!namePart.contains(':')) { "Invalid image reference format: multiple colons" }

            // Split namespace/repository
            val slashIndex = namePart.indexOf('/')
            val (namespace, repository) = if (slashIndex >= 0) {
                namePart.substring(0, slashIndex) to namePart.substring(slashIndex + 1)
            } else {
                "library" to namePart
            }

            require(namespace.isNotEmpty()) { "Namespace cannot be empty" }
            require(repository.isNotEmpty()) { "Repository name cannot be empty" }

            return DockerImageRef(
                namespace = namespace,
                repository = repository,
                tag = tagPart,
                digest = digestPart
            )
        }

        private fun parseReference(input: String): Triple<String, String, String> {
            // Check for digest first (@sha256:...)
            val atIndex = input.lastIndexOf('@')
            if (atIndex >= 0) {
                val namePart = input.substring(0, atIndex)
                val digestPart = input.substring(atIndex + 1)
                require(digestPart.startsWith("sha256:")) { "Only sha256 digests are supported" }
                return Triple(namePart, "", digestPart)
            }

            // Parse tag (last colon)
            val colonIndex = input.lastIndexOf(':')
            if (colonIndex >= 0) {
                val afterColon = input.substring(colonIndex + 1)
                // Ensure it's a valid tag (no slashes, not empty)
                require(afterColon.isNotEmpty() && !afterColon.contains('/')) {
                    "Invalid tag format"
                }
                val namePart = input.substring(0, colonIndex)
                return Triple(namePart, afterColon, "")
            }

            return Triple(input, "latest", "")
        }
    }
}
