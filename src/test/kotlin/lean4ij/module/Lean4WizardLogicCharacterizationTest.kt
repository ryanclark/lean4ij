package lean4ij.module

import lean4ij.project.ElanService
import lean4ij.sdk.mangleToolchainDir
import lean4ij.sdk.toolchainSdkName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Proxy

/**
 * Characterization tests that pin the CURRENT pure string/transform behavior of the
 * new-project wizard subsystem (for the upcoming Lean4ModuleType split). These tests
 * only exercise pure logic that does NOT require an Application, WizardContext,
 * PropertyGraph, or any network access.
 *
 * The following pure helpers were extracted (behavior-preservingly) from production code
 * so they can be pinned here:
 *  - [buildLakeCommand]  (from QuickStarterModel.lakeCommand)
 *  - [proxyFromUrl]      (from QuickStarterModel.getVersions proxy construction)
 *  - [mangleToolchainDir] (from SdkService.getHomePath toolchain-dir mangling)
 *  - [toolchainSdkName]  (from SdkService.setupModule sdk name derivation)
 */
class Lean4WizardLogicCharacterizationTest {

    // region SdkService.getHomePath toolchain-dir mangling: '/' -> '--', ':' -> '---'

    @Test
    fun mangleToolchainDir_replacesSlashAndColon() {
        // A typical lean-toolchain content: "leanprover/lean4:v4.18.0"
        // '/' becomes "--" and ':' becomes "---".
        assertEquals("leanprover--lean4---v4.18.0", mangleToolchainDir("leanprover/lean4:v4.18.0"))
    }

    @Test
    fun mangleToolchainDir_orderingSlashThenColon() {
        // The replace order is slash-first then colon. There is no overlap between the two
        // substitutions (neither '--' nor '---' contains a raw '/' or ':'), so the result is
        // deterministic regardless of order; pin the exact output.
        assertEquals("a--b---c", mangleToolchainDir("a/b:c"))
    }

    @Test
    fun mangleToolchainDir_multipleOccurrences() {
        // "//::" -> each '/' -> "--" gives "----::", then each ':' -> "---" gives
        // "----" + "---" + "---" = ten dashes total.
        assertEquals("----------", mangleToolchainDir("//::"))
    }

    @Test
    fun mangleToolchainDir_noSpecialChars_isIdentity() {
        assertEquals("stable", mangleToolchainDir("stable"))
    }

    @Test
    fun mangleToolchainDir_emptyString() {
        assertEquals("", mangleToolchainDir(""))
    }

    // endregion

    // region SdkService.setupModule sdk name: split('/').last().replace(':', ' ')

    @Test
    fun toolchainSdkName_typicalToolchain() {
        // "leanprover/lean4:v4.18.0" -> last path segment "lean4:v4.18.0" -> ':' to ' '
        assertEquals("lean4 v4.18.0", toolchainSdkName("leanprover/lean4:v4.18.0"))
    }

    @Test
    fun toolchainSdkName_noSlash_usesWholeString() {
        assertEquals("v4.18.0", toolchainSdkName("v4.18.0"))
    }

    @Test
    fun toolchainSdkName_noColon_keepsSegment() {
        assertEquals("lean4", toolchainSdkName("leanprover/lean4"))
    }

    @Test
    fun toolchainSdkName_onlyFirstColonReplacedPerOccurrence() {
        // replace(Char, Char) replaces every ':' in the last segment, not just the first.
        assertEquals("a b c", toolchainSdkName("owner/a:b:c"))
    }

    // endregion

    // region QuickStarterModel.lakeCommand pure core: buildLakeCommand

    @Test
    fun buildLakeCommand_defaultTemplateAndLanguage_onlyName() {
        // TEMPLATES[0] == "std", LANGUAGES[0] == "lean": both default -> no suffixes.
        assertEquals("std", QuickStarterModel.TEMPLATES[0])
        assertEquals("lean", QuickStarterModel.LANGUAGES[0])
        assertEquals("lake new Untitled", buildLakeCommand("Untitled", "std", "lean"))
    }

    @Test
    fun buildLakeCommand_nonDefaultTemplate_defaultLanguage_appendsTemplate() {
        assertEquals("lake new MyProj exe", buildLakeCommand("MyProj", "exe", "lean"))
    }

    @Test
    fun buildLakeCommand_defaultTemplate_nonDefaultLanguage_appendsSpaceThenDotLang() {
        // Default template + non-default language: a single space is appended before ".toml".
        assertEquals("lake new MyProj .toml", buildLakeCommand("MyProj", "std", "toml"))
    }

    @Test
    fun buildLakeCommand_nonDefaultTemplate_nonDefaultLanguage_appendsTemplateThenDotLang() {
        // Non-default template + non-default language: template adds its own leading space,
        // and because the template is non-default the extra space before ".toml" is NOT added.
        assertEquals("lake new MyProj math.toml", buildLakeCommand("MyProj", "math", "toml"))
    }

    @Test
    fun buildLakeCommand_libTemplate_defaultLanguage() {
        assertEquals("lake new MyProj lib", buildLakeCommand("MyProj", "lib", "lean"))
    }

    @Test
    fun buildLakeCommand_emptyName_stillBuilds() {
        // entityName is interpolated verbatim with no validation.
        assertEquals("lake new ", buildLakeCommand("", "std", "lean"))
    }

    // endregion

    // region QuickStarterModel.getVersions proxy construction: proxyFromUrl

    @Test
    fun proxyFromUrl_httpUrl_yieldsHttpProxyTypeWithLiteralIp() {
        val proxy = proxyFromUrl("http://127.0.0.1:7890")
        assertEquals(Proxy.Type.HTTP, proxy.type())
        // With a literal IPv4 host, InetSocketAddress resolves immediately and Proxy.toString()
        // renders as "<TYPE> @ /<ip>:<port>".
        assertEquals("HTTP @ /127.0.0.1:7890", proxy.toString())
        val addr = proxy.address() as java.net.InetSocketAddress
        assertEquals(7890, addr.port)
        assertEquals("127.0.0.1", addr.hostString)
    }

    @Test
    fun proxyFromUrl_httpsUrl_yieldsHttpProxyType() {
        // protocol "https" does not contain "sock" -> HTTP type. (Hostname resolution is
        // environment dependent, so only the type and port are pinned here.)
        val proxy = proxyFromUrl("https://proxy.example.com:8080")
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val addr = proxy.address() as java.net.InetSocketAddress
        assertEquals(8080, addr.port)
        assertEquals("proxy.example.com", addr.hostString)
    }

    @Test
    fun proxyFromUrl_noPort_usesMinusOne() {
        // URL.getPort() returns -1 when no explicit port is present; this flows straight into
        // the InetSocketAddress. (-1 is out of the legal 0..65535 range, so the constructor
        // throws IllegalArgumentException -- this pins that current behavior.)
        try {
            proxyFromUrl("http://127.0.0.1")
            fail("expected IllegalArgumentException for port -1")
        } catch (e: IllegalArgumentException) {
            // expected: "port out of range:-1"
            assertTrue(e.message!!.contains("-1"))
        }
    }

    @Test
    fun proxyFromUrl_socksUrl_throwsBecauseJvmRejectsUnknownProtocol() {
        // CHARACTERIZATION: the JVM's URL only recognizes a fixed set of protocols
        // (http/https/ftp/file/jar). "socks" is NOT registered in a plain unit-test runtime,
        // so URL(...) throws MalformedURLsuffix BEFORE the protocol.contains("sock") branch is
        // ever evaluated. The SOCKS branch is therefore unreachable in this environment.
        try {
            proxyFromUrl("socks://127.0.0.1:1080")
            fail("expected MalformedURLException for socks:// in a plain JVM")
        } catch (e: java.net.MalformedURLException) {
            assertTrue(e.message!!.contains("socks"))
        }
    }

    @Test
    fun proxyFromUrl_socks5Url_throwsBecauseJvmRejectsUnknownProtocol() {
        try {
            proxyFromUrl("socks5://127.0.0.1:1080")
            fail("expected MalformedURLException for socks5:// in a plain JVM")
        } catch (e: java.net.MalformedURLException) {
            assertTrue(e.message!!.contains("socks5"))
        }
    }

    // endregion

    // region ElanService.toolchains: bundled toolchains.txt resource split on "\n"

    @Test
    fun toolchains_splitsBundledResourceOnNewline() {
        // ElanService construction is pure here: its field initializers only call
        // System.getProperty("user.home") and Path.of(...), neither of which needs an Application.
        val elan = ElanService()
        val versions = elan.toolchains(includeRemote = true)

        // The resource has no trailing newline, so split("\n") produces NO empty trailing element.
        assertFalse("split result must not contain an empty element", versions.contains(""))
        assertEquals("v4.24.0", versions.first())
        assertEquals("v4.0.0", versions.last())
        assertTrue("expected a known middle version", versions.contains("v4.18.0"))

        // The includeRemote flag is currently ignored by toolchains(); both values are identical.
        assertEquals(versions, elan.toolchains(includeRemote = false))

        // Pin the exact transform: equal to reading the resource text and splitting on "\n".
        val raw = ElanService::class.java.classLoader.getResource("toolchains.txt")!!.readText()
        assertEquals(raw.split("\n"), versions)
    }

    @Test
    fun toolchains_allEntriesNonBlank() {
        val versions = ElanService().toolchains(includeRemote = true)
        // Every entry is a non-blank version tag (pins the no-trailing-newline invariant strongly).
        assertTrue(versions.all { it.isNotBlank() })
    }

    // endregion

    // region defensive pins on the constant lists used by the wizard

    @Test
    fun templatesAndLanguages_constantsAreStable() {
        assertEquals(listOf("std", "exe", "lib", "math"), QuickStarterModel.TEMPLATES)
        assertEquals(listOf("lean", "toml"), QuickStarterModel.LANGUAGES)
        // first() is what the default-detection in buildLakeCommand keys off of.
        assertSame(QuickStarterModel.TEMPLATES[0], QuickStarterModel.TEMPLATES.first())
    }

    // endregion
}
