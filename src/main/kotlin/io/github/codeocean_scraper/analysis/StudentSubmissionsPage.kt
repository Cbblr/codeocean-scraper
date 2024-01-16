package io.github.codeocean_scraper.analysis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.codeocean_scraper.PageFetcher
import io.github.codeocean_scraper.RelevancyType
import io.github.codeocean_scraper.parsing.Submission
import io.github.codeocean_scraper.parsing.SubmissionCause
import io.github.codeocean_scraper.parsing.SubmissionFile
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

private val HEADER_TEMPLATE = """//
// Submissions page: %s
// Score: %s
//

"""

private fun parseSubmissions(
        dataNode: Element,
        mapper: ObjectMapper
): List<Submission> {
    return mapper.readValue<List<Submission>>(dataNode.attr("data-submissions"))
}

private fun findRelevantlyPlacedSubmissions(
        submissions: List<Submission>
): List<Pair<RelevancyType, Submission>> {
    val lastSubmitIndex = submissions.indexOfLast { it.cause == SubmissionCause.SUBMIT }
    val lastSubmit = if (lastSubmitIndex >= 0) submissions[lastSubmitIndex] else null
    if (lastSubmit != null) {
        val assessAfterSubmit = submissions
                .asSequence()
                .drop(lastSubmitIndex + 1)
                .lastOrNull { it.cause == SubmissionCause.ASSESS }
        if (assessAfterSubmit != null) {
            return listOf(
                    RelevancyType.SUBMITTED to lastSubmit,
                    RelevancyType.ASSESSED_AFTER_SUBMIT to assessAfterSubmit
            )
        }

        return listOf(RelevancyType.SUBMITTED to lastSubmit)
    }

    val assessedOnly = submissions.lastOrNull { it.cause == SubmissionCause.ASSESS }
    if (assessedOnly != null) {
        return listOf(RelevancyType.ASSESSED_ONLY to assessedOnly)
    }

    return if (submissions.any())
        listOf(RelevancyType.NO_ASSESS_OR_SUBMIT to submissions.last())
        else listOf()
}

private fun findRelevantSubmissions(
        submissions: List<Submission>
): List<Pair<RelevancyType, Submission>> {
    val relevantlyPlaced = findRelevantlyPlacedSubmissions(submissions)
    val maxScore = relevantlyPlaced
            .asSequence()
            .map { it.second.score }
            .filterNotNull()
            .max() ?: -1.0
    val topScored = submissions.lastOrNull { it.score != null && it.score > maxScore }
    return if (topScored != null)
        relevantlyPlaced.plusElement(RelevancyType.TOP_SCORE to topScored)
        else relevantlyPlaced
}

private fun parseSubmissionFiles(
        dataNode: Element,
        mapper: ObjectMapper
): Map<Int, List<SubmissionFile>> {
    val filesList = mapper.readValue<List<List<SubmissionFile>>>(dataNode.attr("data-files"))
    return filesList
            .filter { it.isNotEmpty() }
            .map { it.first().contextId to it }
            .toMap()
}

private fun saveFiles(
        submissionsPageUrl: String,
        relevantSubmissions: List<Pair<RelevancyType, Submission>>,
        submissionFiles: Map<Int, List<SubmissionFile>>,
        deadlineExceeded: Boolean,
        studentDirectory: Path,
        fileSuffix: String
) {
    for ((relevancy, submission) in relevantSubmissions) {
        val files = submissionFiles[submission.id]!!

        val submissionScore = submission.score?.toString() ?: "Not scored";

        val dir: Path;
        val postfix: String;
        val header: String;

        if (files.size > 1) {
            dir = studentDirectory.resolve(relevancy.toFileString());
            Files.createDirectories(dir);

            header = ""
            postfix = ""

            dir.resolve("score.txt").toFile().writeText(submissionScore + "\n");
            dir.resolve("url.txt").toFile().writeText(submissionsPageUrl + "\n");
        } else {
            dir = studentDirectory
            postfix = relevancy.toFilePostfix()

            header = HEADER_TEMPLATE.format(submissionsPageUrl, submissionScore)
        }

        if (deadlineExceeded) {
            dir.resolve("DEADLINE_EXCEEDED").toFile().writeText("");
        }

        for (file in files) {
            val fileName = "${file.name}$postfix.$fileSuffix"
            val filePath = dir.resolve(fileName)
            filePath.toFile().writeText(header + file.content)
        }
    }
}

fun createStudentDirectory(studentsDirectory: Path, studentName: String): Path {
    val studentDirectory = studentsDirectory.resolve(studentName)

    Files.createDirectories(studentDirectory)

    return studentDirectory
}

fun analyseAndSave(
        submissionsPageUrl: String,
        fetcher: PageFetcher,
        studentName: String,
        deadline: OffsetDateTime,
        studentsDirectory: Path,
        mapper: ObjectMapper,
        fileSuffix: String
) {
    require(studentsDirectory.toFile().isDirectory) { "Students directory must be a directory" }
    val studentDirectory = createStudentDirectory(studentsDirectory, studentName)
    val document: Document
    try {
        document = fetcher.fetch(submissionsPageUrl)
    } catch (e: HttpStatusException) {
        studentDirectory.resolve("error.txt").toFile().writeText(e.toString())
        return
    }

    val dataNode = document.select("#data").first()
    val submissions = parseSubmissions(dataNode, mapper)
    val relevantSubmissions = findRelevantSubmissions(submissions)
    val deadlineExceeded = relevantSubmissions.any { it.second.updatedAt >= deadline }
    val submissionFiles = parseSubmissionFiles(dataNode, mapper)
    saveFiles(submissionsPageUrl, relevantSubmissions, submissionFiles, deadlineExceeded, studentDirectory, fileSuffix)
}
