package com.codetouml.dto;

/**
 * Request to diagram a whole public GitHub repository.
 *
 * @param repoUrl a github.com/owner/repo URL (optionally a /tree/&lt;branch&gt;/&lt;subpath&gt; to scope it)
 */
public record GenerateRepoRequest(String repoUrl) {
}
