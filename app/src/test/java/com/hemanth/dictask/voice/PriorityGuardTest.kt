package com.hemanth.dictask.voice

import com.hemanth.dictask.data.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The model spreads "it's urgent" to every task in a sentence. These are model outputs seen in testing
 * (gpt-oss-120b and 20b), and what Groq.guardPriorities must turn them into.
 */
class PriorityGuardTest {

    private fun add(title: String, priority: Int = Priority.NONE) =
        Draft(title, "", null, null, priority, null, null, null)

    private fun priorities(text: String, vararg drafts: Draft) =
        Groq.guardPriorities(text, drafts.toList()).map { it.priority }

    private val H = Priority.HIGH
    private val N = Priority.NONE

    @Test fun urgentAtTheEndBelongsOnlyToTheLastTask() = assertEquals(
        listOf(N, N, H),
        priorities(
            "Call the electrician tomorrow at 10, buy milk and eggs tonight, and pay the credit card bill by Friday, it's urgent",
            add("Call the electrician", H), add("Buy milk and eggs", H), add("Pay the credit card bill", H),
        ),
    )

    @Test fun leadingUrgentBelongsToTheFirstTask() = assertEquals(
        listOf(H, N),
        priorities(
            "Urgent: renew my passport next week, and water the plants tomorrow",
            add("Renew my passport", H), add("Water the plants", H),
        ),
    )

    @Test fun asapInTheMiddleStaysWithItsTask() = assertEquals(
        listOf(H, N),
        priorities("Book the flight tickets asap and call mom this evening", add("Book flight tickets", H), add("Call mom", H)),
    )

    @Test fun noPriorityWordMeansNoPriority() = assertEquals(
        listOf(N),
        priorities("Remind me to take medicine at 9 pm every day", add("Take medicine", H)),
    )

    @Test fun aSingleTaskKeepsItsPriority() = assertEquals(
        listOf(H),
        priorities("Submit the assignment tomorrow morning, it's very urgent", add("Submit the assignment", H)),
    )

    @Test fun droppedPriorityOnAnEditMeansUnchanged() {
        val edit = Draft("", "", null, null, H, null, null, null, kind = Kind.UPDATE, targetId = 7)
        assertEquals(listOf(null), priorities("Move the dentist to Friday", edit))
    }

    @Test fun explicitAmIsRecognised() {
        assertEquals(true, Groq.saidAm("Gym every weekday at 7am", 7))
        assertEquals(true, Groq.saidAm("call mom at 7:30 a.m. tomorrow", 7))
        assertEquals(true, Groq.saidAm("run at 6 in the morning", 6))
        assertEquals(false, Groq.saidAm("pay the bill by 10", 10))
        assertEquals(false, Groq.saidAm("meeting at 7 pm and gym at 17am", 7))
    }

    @Test fun taskThatCantBeFoundInTheSentenceKeepsTheModelsAnswer() = assertEquals(
        listOf(H, N),
        // Titles are English even when the speech wasn't, so a task may not be locatable: trust the model then.
        priorities("addhe kattali friday urgent, and buy milk", add("Pay rent", H), add("Buy milk")),
    )
}
