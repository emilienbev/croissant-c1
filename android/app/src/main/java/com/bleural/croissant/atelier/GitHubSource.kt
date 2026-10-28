package com.bleural.croissant.atelier

import com.bleural.croissant.update.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A GitHub repository as typed (`owner/repo`, `owner/repo@ref`) or scanned (its URL), with
 * the app at its root. [ref] null: the default branch.
 */
data class RepoRef(val owner: String, val name: String, val ref: String? = null) {
    val slug get() = "$owner/$name"

    companion object {
        private val TREE = Regex("/tree/([^?#\\s]+)")
        /** A branch, tag or commit; it goes into a URL path, so no slash. */
        private val REF = Regex("[A-Za-z0-9._-]{1,100}")

        /**
         * What the add page's field or a QR code holds: `owner/repo` or a github.com link to
         * it, as the update source reads them ([Source.parse]), with an optional ref, typed
         * as `@ref` or linked as `/tree/ref`. Null for anything else.
         */
        fun parse(input: String): RepoRef? {
            var s = input.trim()
            var ref: String? = null
            val tree = TREE.find(s)
            if (tree != null) {
                ref = tree.groupValues[1].trimEnd('/')
                s = s.substring(0, tree.range.first)
            } else if ('@' in s) {
                ref = s.substringAfterLast('@')
                s = s.substringBeforeLast('@')
            }
            if (ref != null && !REF.matches(ref)) return null
            val src = Source.parse(s) ?: return null
            return RepoRef(src.owner, src.repo, ref)
        }
    }
}

/** Why an app could not be fetched, in the add page's words. */
sealed class AddFailure(message: String) : Exception(message) {
    class NoSuchRepo : AddFailure("no such repo on github")
    class NoApp : AddFailure("no ${AppManifest.FILE} in it: not a Croissant app")
    class Bad(message: String) : AddFailure(message)
    class Offline(message: String) : AddFailure(message)
}

/**
 * Fetches an app from GitHub: the commit [RepoRef.ref] (or the default branch) points at,
 * then that commit's `croissant.json` and `croissant.js` from its zip, staged under its
 * repo's name ([AppId.of]; the store settles the id, [AtelierStore.place]). Unauthenticated: the commit
 * lookup counts against GitHub's 60 an hour per address, the zip does not.
 */
class GitHubSource(private val client: OkHttpClient, private val userAgent: String) {

    suspend fun fetch(repo: RepoRef): StagedApp = withContext(Dispatchers.IO) {
        val sha = commit(repo)
        stage(repo, sha, zip(repo, sha))
    }

    private fun commit(repo: RepoRef): String {
        val url = "https://api.github.com/repos/${repo.slug}/commits/${repo.ref ?: "HEAD"}"
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github.sha")
            .build()
        val r = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw AddFailure.Offline("github cannot be reached")
        }
        r.use {
            when (it.code) {
                200 -> {}
                404, 422 -> throw AddFailure.NoSuchRepo()
                403, 429 -> throw AddFailure.Offline("github asks to wait a while")
                else -> throw AddFailure.Offline("github answered ${it.code}")
            }
            val sha = it.body.string().trim()
            if (!SHA.matches(sha)) throw AddFailure.Offline("github answered strangely")
            return sha
        }
    }

    /** The two files at the repository's root, from the commit's zip ([files]); the rest is skipped. */
    private fun zip(repo: RepoRef, sha: String): Map<String, String> {
        val req = Request.Builder().url("https://codeload.github.com/${repo.slug}/zip/$sha")
            .header("User-Agent", userAgent).build()
        val r = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw AddFailure.Offline("github cannot be reached")
        }
        r.use {
            if (it.code == 404) throw AddFailure.NoSuchRepo()
            if (!it.isSuccessful) throw AddFailure.Offline("github answered ${it.code}")
            return files(it.body.byteStream())
        }
    }

    companion object {
        private val SHA = Regex("[0-9a-f]{40}")
        private val WANTED = setOf(AppManifest.FILE, AppManifest.SCRIPT)

        /** [repo]'s app at [sha] from the two [files], staged under the repo's name ([AppId.of]), or why not. */
        fun stage(repo: RepoRef, sha: String, files: Map<String, String>): StagedApp {
            val json = files[AppManifest.FILE] ?: throw AddFailure.NoApp()
            val manifest = AppManifest.parse(json).getOrElse { throw AddFailure.Bad(it.message ?: "its ${AppManifest.FILE} is wrong") }
            val script = files[AppManifest.SCRIPT] ?: throw AddFailure.Bad("no ${AppManifest.SCRIPT} in it")
            return StagedApp(AppId.of(repo.name), manifest, script, AppSource.GitHub(repo.slug, sha))
        }

        /** The two files at the root of a commit's [zip], whose entries all sit under one top folder, "<repo>-<sha>/". */
        fun files(zip: InputStream): Map<String, String> {
            val out = mutableMapOf<String, String>()
            ZipInputStream(zip).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val path = e.name.substringAfter('/', "")
                    if (e.isDirectory || path !in WANTED) continue
                    out[path] = read(z, path)
                }
            }
            return out
        }

        private fun read(zip: ZipInputStream, path: String): String {
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = zip.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > AtelierStore.MAX_SCRIPT) throw AddFailure.Bad("$path is over ${AtelierStore.MAX_SCRIPT / 1024} kB")
            }
            return buf.toString(Charsets.UTF_8.name())
        }
    }
}
