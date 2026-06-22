package com.codetouml.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a public GitHub repository as a zip and extracts its Java sources, so the whole repo
 * can be diagrammed at once. Only github.com URLs are accepted (no arbitrary fetch = no SSRF), and
 * the amount of code is capped to protect the small server and keep the diagram readable.
 */
@Service
public class GitHubRepoService {

    // github.com/owner/repo  (optionally .git, a trailing slash, or /tree/<branch>/<subpath>)
    private static final Pattern REPO = Pattern.compile(
            "github\\.com[/:]([\\w.-]+)/([\\w.-]+?)(?:\\.git)?/?(?:tree/[^/]+/?(.*))?$");

    private static final int MAX_FILES = 80;
    private static final long MAX_TOTAL_BYTES = 2_000_000;   // ~2 MB of Java total
    private static final long MAX_FILE_BYTES = 200_000;      // skip a single giant file

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public record RepoSources(String owner, String repo, List<String> javaSources, int skipped) {}

    public RepoSources fetchJavaSources(String repoUrl) {
        if (repoUrl == null) {
            throw new IllegalArgumentException("Please provide a GitHub repo URL.");
        }
        Matcher m = REPO.matcher(repoUrl.trim());
        if (!m.find()) {
            throw new IllegalArgumentException("Not a valid GitHub repo URL (expected github.com/owner/repo).");
        }
        String owner = m.group(1);
        String repo = m.group(2);
        String subPath = m.group(3);   // optional folder filter from a /tree/<branch>/<path> URL

        // The API zipball endpoint redirects to the default branch's archive.
        byte[] zip = download("https://api.github.com/repos/" + owner + "/" + repo + "/zipball");

        List<String> sources = new ArrayList<>();
        long total = 0;
        int skipped = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                // Strip the top-level "owner-repo-sha/" folder the zipball wraps everything in.
                String name = e.getName();
                String rel = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;

                if (!rel.endsWith(".java")
                        || rel.endsWith("package-info.java") || rel.endsWith("module-info.java")
                        || rel.contains("/test/") || rel.contains("/generated/")) {
                    continue;
                }
                if (subPath != null && !subPath.isBlank() && !rel.startsWith(subPath)) {
                    continue;
                }
                byte[] content = zis.readAllBytes();
                if (content.length > MAX_FILE_BYTES
                        || sources.size() >= MAX_FILES
                        || total + content.length > MAX_TOTAL_BYTES) {
                    skipped++;
                    continue;
                }
                sources.add(new String(content, StandardCharsets.UTF_8));
                total += content.length;
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read the repository archive.", ex);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("No Java files found in that repository"
                    + (subPath != null && !subPath.isBlank() ? " under '" + subPath + "'." : "."));
        }
        return new RepoSources(owner, repo, sources, skipped);
    }

    private byte[] download(String url) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "code-to-uml")
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(45));
            String token = System.getenv("GITHUB_TOKEN");   // optional — raises the rate limit
            if (token != null && !token.isBlank()) {
                req.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code == 404) {
                throw new IllegalArgumentException("Repository not found — is it public?");
            }
            if (code == 403) {
                throw new IllegalArgumentException("GitHub rate limit reached — please try again in a bit.");
            }
            if (code >= 300) {
                throw new RuntimeException("GitHub returned HTTP " + code + " for the repo archive.");
            }
            return resp.body();
        } catch (IOException ex) {
            throw new RuntimeException("Could not download the repository from GitHub.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Download was interrupted.", ex);
        }
    }
}
