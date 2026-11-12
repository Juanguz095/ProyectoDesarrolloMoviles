package com.auditlab.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.auditlab.common.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentModulesJournalTest {
    private val url = "https://qa.example.invalid"
    private val consent = ConsentContext(UUID.randomUUID().toString(), "mvp-1", "2026-10-25T10:00:00Z")
    private val credential = StoredCredential(UUID.randomUUID().toString(), "fixture-only-never-in-room", consent, url)
    private fun io(action: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try { pool.submit(Callable { action() }).get(90, TimeUnit.SECONDS) } finally { pool.shutdownNow() }
    }
    private fun fixture(action: (RoomAgentJournal) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "qa-modules-${UUID.randomUUID()}.db"
        io {
            val db = AgentDatabase.open(context, name)
            try { action(RoomAgentJournal(db)) } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun `upgraded device has no modules until it re-accepts`() = fixture { journal ->
        journal.activate(credential, url)
        assertTrue(journal.modules().isEmpty())
    }

    @Test fun `acceptance registers every available module as accepted`() = fixture { journal ->
        journal.activate(credential, url)
        journal.acceptModules("2026-11-11T10:00:00Z")
        val modules = journal.modules()
        assertEquals(ConsentModulesPolicy.available.sorted(), modules.map { it.module }.sorted())
        assertTrue(modules.all { it.state == ModuleState.ACCEPTED && it.acceptedAt == "2026-11-11T10:00:00Z" })
    }

    @Test fun `a module pauses revokes and can be accepted again`() = fixture { journal ->
        journal.activate(credential, url); journal.acceptModules("2026-11-11T10:00:00Z")
        val module = ConsentModulesPolicy.WHATSAPP
        assertTrue(journal.setModuleState(module, ModuleState.PAUSED))
        assertEquals(ModuleState.PAUSED, journal.modules().single { it.module == module }.state)
        assertTrue(journal.setModuleState(module, ModuleState.ACCEPTED))
        assertTrue(journal.setModuleState(module, ModuleState.REVOKED))
        assertEquals(ModuleState.REVOKED, journal.modules().single { it.module == module }.state)
        assertTrue(journal.setModuleState(module, ModuleState.ACCEPTED))
        assertEquals(ModuleState.ACCEPTED, journal.modules().single { it.module == module }.state)
    }

    @Test fun `unknown and reserved modules are refused`() = fixture { journal ->
        journal.activate(credential, url)
        assertFalse(journal.setModuleState("camera", ModuleState.PAUSED))
        journal.acceptModules("2026-11-11T10:00:00Z")
        assertFalse(journal.setModuleState(ConsentModulesPolicy.VIDEO, ModuleState.PAUSED))
    }

    @Test fun `a checked function is accepted even without prior rows`() = fixture { journal ->
        journal.activate(credential, url)
        assertTrue(journal.setModuleState(ConsentModulesPolicy.BASE, ModuleState.ACCEPTED))
        assertTrue(journal.setModuleState(ConsentModulesPolicy.SCREEN, ModuleState.ACCEPTED))
        assertEquals(setOf(ConsentModulesPolicy.BASE, ConsentModulesPolicy.SCREEN),
            journal.modules().filter { it.state == ModuleState.ACCEPTED }.map { it.module }.toSet())
    }

    @Test fun `withdrawing the general consent clears every module`() = fixture { journal ->
        journal.activate(credential, url); journal.acceptModules("2026-11-11T10:00:00Z")
        journal.beginRevocation(credential.deviceId, url, false)
        assertTrue(journal.modules().isEmpty())
    }
}
