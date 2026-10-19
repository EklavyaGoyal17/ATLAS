package dev.atlas.intelligence;

import dev.atlas.platform.ApiException;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Imports an immutable public GitHub archive entirely in memory, without executing it. */
@Component
public class GitHubRepositoryImport {

    @FunctionalInterface
    interface Fetcher {
        Reply fetch(URI uri, int maxBytes) throws Exception;
    }

    record Reply(int status, String location, byte[] body) {}

    private record Repository(String owner, String name, String canonical) {}

    private record Unpacked(
        List<RepositoryIndex.RemoteFile> files,
        List<String> findings
    ) {}

    private static final int ARCHIVE_BYTES = 16 * 1024 * 1024;
    private static final long EXPANDED_BYTES = 32L * 1024 * 1024;
    private final RepositoryIndex index;
    private final JsonMapper json;
    private final Fetcher fetcher;
    private final Semaphore gate = new Semaphore(1);

    @Autowired
    public GitHubRepositoryImport(RepositoryIndex index, JsonMapper json) {
        this(index, json, new PublicGitHubClient());
    }

    GitHubRepositoryImport(
        RepositoryIndex index,
        JsonMapper json,
        Fetcher fetcher
    ) {
        this.index = index;
        this.json = json;
        this.fetcher = fetcher;
    }

    public RepositoryIndex.Snapshot importRepository(
        String url,
        String directory,
        boolean approved
    ) {
        if (!approved) throw failure(
            HttpStatus.BAD_REQUEST,
            "IMPORT_APPROVAL_REQUIRED",
            "Approve read-only public GitHub import first."
        );
        Repository repository = repository(url);
        String scope = directory == null ? "" : directory;
        validateDirectory(scope);
        if (!gate.tryAcquire()) throw failure(
            HttpStatus.TOO_MANY_REQUESTS,
            "GITHUB_IMPORT_BUSY",
            "Another public GitHub import is in progress. Try again after it finishes."
        );
        try {
            long revision = index.importRevision();
            String route =
                "https://api.github.com/repos/" +
                repository.owner() +
                "/" +
                repository.name();
            Reply commits = fetch(
                URI.create(route + "/commits?per_page=1"),
                128 * 1024
            );
            requireStatus(commits, 200);
            var metadata = json.readTree(commits.body());
            if (!metadata.isArray() || metadata.isEmpty()) throw unavailable();
            if (!metadata.get(0).path("sha").isString()) throw unavailable();
            String commit = metadata.get(0).path("sha").asString("");
            if (!commit.matches("[a-fA-F0-9]{40}")) throw unavailable();
            commit = commit.toLowerCase(Locale.ROOT);
            Reply archive = fetch(
                URI.create(route + "/zipball/" + commit),
                128 * 1024
            );
            requireStatus(archive, 302);
            URI target = archiveTarget(archive.location(), repository, commit);
            Reply downloaded = fetch(target, ARCHIVE_BYTES);
            requireStatus(downloaded, 200);
            var unpacked = unpack(downloaded.body(), scope);
            return index.indexRemote(
                revision,
                unpacked.files(),
                new RepositoryIndex.Origin(
                    "GITHUB",
                    repository.canonical(),
                    commit,
                    scope
                ),
                unpacked.findings()
            );
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        } finally {
            gate.release();
        }
    }

    private Reply fetch(URI uri, int maxBytes) throws Exception {
        Reply reply;
        try {
            reply = fetcher.fetch(uri, maxBytes);
        } catch (PublicGitHubClient.ResponseTooLargeException e) {
            if (maxBytes == ARCHIVE_BYTES) throw archiveLimit();
            throw unavailable();
        }
        if (reply == null || reply.body() == null) throw unavailable();
        if (reply.body().length > maxBytes) {
            if (maxBytes == ARCHIVE_BYTES) throw archiveLimit();
            throw unavailable();
        }
        return reply;
    }

    private static Repository repository(String value) {
        try {
            if (
                value == null ||
                value.length() > 300 ||
                value.contains("%") ||
                value.contains("\\") ||
                value.chars().anyMatch(Character::isISOControl)
            ) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            if (
                !"https".equals(uri.getScheme()) ||
                !"github.com".equals(uri.getHost()) ||
                !"github.com".equals(uri.getRawAuthority()) ||
                uri.getRawUserInfo() != null ||
                uri.getPort() != -1 ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null
            ) throw new IllegalArgumentException();
            String path = uri.getRawPath();
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String[] parts = path.split("/", -1);
            if (
                parts.length != 3 || !parts[0].isEmpty()
            ) throw new IllegalArgumentException();
            String owner = parts[1],
                name = parts[2];
            if (name.endsWith(".git")) name = name.substring(
                0,
                name.length() - 4
            );
            if (
                !owner.matches(
                    "[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"
                ) ||
                !name.matches("[A-Za-z0-9_.-]{1,100}") ||
                name.equals(".") ||
                name.equals("..")
            ) throw new IllegalArgumentException();
            return new Repository(
                owner,
                name,
                "https://github.com/" + owner + "/" + name
            );
        } catch (IllegalArgumentException e) {
            throw failure(
                HttpStatus.BAD_REQUEST,
                "UNSAFE_GITHUB_URL",
                "Use a public repository URL such as https://github.com/owner/repository, without a branch, credentials or query."
            );
        }
    }

    private static void validateDirectory(String directory) {
        if (
            directory.length() > 200 ||
            (!directory.isEmpty() && !safeComponents(directory))
        ) throw failure(
            HttpStatus.BAD_REQUEST,
            "UNSAFE_GITHUB_DIRECTORY",
            "Choose a relative repository subfolder with no dot or parent components."
        );
    }

    private static boolean safeComponents(String path) {
        if (
            path.isEmpty() ||
            path.contains("\\") ||
            path.contains(":") ||
            path.chars().anyMatch(Character::isISOControl)
        ) return false;
        return Arrays.stream(path.split("/", -1)).noneMatch(
            p -> p.isEmpty() || p.equals(".") || p.equals("..")
        );
    }

    private static URI archiveTarget(
        String location,
        Repository repository,
        String commit
    ) {
        try {
            if (
                location == null ||
                location.contains("%") ||
                location.contains("\\")
            ) throw new IllegalArgumentException();
            URI uri = URI.create(location);
            if (
                !"https".equals(uri.getScheme()) ||
                !"codeload.github.com".equals(uri.getHost()) ||
                !"codeload.github.com".equals(uri.getRawAuthority()) ||
                uri.getRawUserInfo() != null ||
                uri.getPort() != -1 ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null
            ) throw new IllegalArgumentException();
            String[] parts = uri.getRawPath().split("/", -1);
            if (
                parts.length != 5 ||
                !parts[0].isEmpty() ||
                !parts[1].equalsIgnoreCase(repository.owner()) ||
                !parts[2].equalsIgnoreCase(repository.name()) ||
                !parts[3].equals("legacy.zip") ||
                !parts[4].equals(commit)
            ) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private static Unpacked unpack(byte[] bytes, String directory)
        throws IOException {
        if (
            bytes.length < 4 ||
            bytes[0] != 'P' ||
            bytes[1] != 'K' ||
            bytes[2] != 3 ||
            bytes[3] != 4
        ) throw unavailable();
        var files = new ArrayList<RepositoryIndex.RemoteFile>();
        var names = new HashSet<String>();
        String root = null;
        int entries = 0;
        int oversized = 0,
            invalidUtf8 = 0,
            nulContent = 0;
        long expanded = 0,
            selected = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (
                    ++entries > 5000 || System.nanoTime() > deadline
                ) throw archiveLimit();
                String name = entry.getName();
                if (entry.isDirectory() && name.endsWith("/")) name =
                    name.substring(0, name.length() - 1);
                if (
                    name.length() > 1000 ||
                    !safeComponents(name) ||
                    !names.add(name)
                ) throw failure(
                    HttpStatus.BAD_REQUEST,
                    "UNSAFE_GITHUB_ARCHIVE",
                    "The repository archive contains an unsafe or duplicate path."
                );
                String[] parts = name.split("/", -1);
                if (parts.length - 1 > 18) throw archiveLimit();
                if (root == null) root = parts[0];
                if (
                    !root.equals(parts[0]) ||
                    (parts.length == 1 && !entry.isDirectory())
                ) throw failure(
                    HttpStatus.BAD_REQUEST,
                    "UNSAFE_GITHUB_ARCHIVE",
                    "The repository archive must contain one root folder."
                );
                String path =
                    parts.length == 1 ? "" : name.substring(root.length() + 1);
                boolean included =
                    !entry.isDirectory() &&
                    (directory.isEmpty() || path.startsWith(directory + "/")) &&
                    RepositoryIndex.supportedPath(path);
                var output = included ? new ByteArrayOutputStream() : null;
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    size += read;
                    if (
                        expanded > EXPANDED_BYTES ||
                        System.nanoTime() > deadline
                    ) throw archiveLimit();
                    if (included && size <= 160_000) output.write(
                        buffer,
                        0,
                        read
                    );
                }
                // Oversized and binary sources are omitted with bounded coverage summaries.
                if (included && size > 160_000) oversized++;
                if (included && size <= 160_000) {
                    String content;
                    try {
                        content = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(output.toByteArray()))
                            .toString();
                    } catch (CharacterCodingException e) {
                        invalidUtf8++;
                        continue;
                    }
                    if (content.indexOf('\0') >= 0) {
                        nulContent++;
                        continue;
                    }
                    selected += size;
                    if (
                        files.size() >= 500 || selected > 4_000_000
                    ) throw selectedLimit();
                    files.add(new RepositoryIndex.RemoteFile(path, content));
                }
            }
        }
        if (files.isEmpty()) throw failure(
            HttpStatus.BAD_REQUEST,
            "EMPTY_GITHUB_SCOPE",
            "No supported UTF-8 source files were found in that repository scope. Choose a source subfolder if the repository is large."
        );
        files.sort(Comparator.comparing(RepositoryIndex.RemoteFile::path));
        var findings = new ArrayList<String>();
        if (oversized > 0) findings.add(
            "GitHub scope: skipped " +
                oversized +
                " supported files above the 160000-byte per-file limit."
        );
        if (invalidUtf8 > 0) findings.add(
            "GitHub scope: skipped " +
                invalidUtf8 +
                " supported files with invalid UTF-8 content."
        );
        if (nulContent > 0) findings.add(
            "GitHub scope: skipped " +
                nulContent +
                " supported files containing NUL bytes."
        );
        return new Unpacked(List.copyOf(files), List.copyOf(findings));
    }

    private static void requireStatus(Reply reply, int expected) {
        if (reply.status() == 403 || reply.status() == 429) throw failure(
            HttpStatus.TOO_MANY_REQUESTS,
            "GITHUB_RATE_LIMIT",
            "GitHub declined this anonymous request or its public rate limit was reached. Try later."
        );
        if (reply.status() == 404) throw failure(
            HttpStatus.NOT_FOUND,
            "GITHUB_REPOSITORY_UNAVAILABLE",
            "The public GitHub repository is unavailable. Private repositories are not supported."
        );
        if (reply.status() != expected) throw unavailable();
    }

    private static ApiException unavailable() {
        return failure(
            HttpStatus.BAD_GATEWAY,
            "GITHUB_UNAVAILABLE",
            "The public GitHub repository could not be fetched safely. Try again later."
        );
    }

    private static ApiException archiveLimit() {
        return failure(
            HttpStatus.BAD_REQUEST,
            "GITHUB_IMPORT_LIMIT",
            "The whole repository archive exceeds safety limits. A source subfolder does not reduce archive transfer; choose a smaller repository."
        );
    }

    private static ApiException selectedLimit() {
        return failure(
            HttpStatus.BAD_REQUEST,
            "GITHUB_IMPORT_LIMIT",
            "The selected source scope exceeds 500 files or 4 MB. Choose a smaller source subfolder or repository."
        );
    }

    private static ApiException failure(
        HttpStatus status,
        String code,
        String message
    ) {
        return new ApiException(status, code, message);
    }
}
