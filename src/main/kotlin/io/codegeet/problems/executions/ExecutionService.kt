package io.codegeet.problems.executions

import io.codegeet.common.*
import io.codegeet.platform.common.*
import io.codegeet.platform.common.ExecutionStatus
import io.codegeet.platform.common.language.Language
import io.codegeet.problems.exceptions.LanguageNotSupportedException
import io.codegeet.problems.executions.exceptions.ExecutionNotFoundException
import io.codegeet.problems.executions.model.*
import io.codegeet.problems.executions.resource.ExecutionResource.ExecutionRequest
import io.codegeet.problems.job.ExecutionJobClient
import io.codegeet.problems.languages.languageTemplates
import io.codegeet.problems.problems.ProblemService
import io.codegeet.problems.problems.model.Problem
import org.springframework.core.io.ClassPathResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.nio.charset.Charset
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class ExecutionService(
    private val executionRepository: ExecutionRepository,
    private val problemService: ProblemService,
    private val executionJobClient: ExecutionJobClient,
    private val clock: Clock,
) {

    fun execute(request: ExecutionRequest): Execution {
        val execution = Execution(
            executionId = UUID.randomUUID().toString(),
            problemId = request.problemId,
            snippet = request.snippet,
            language = request.language,
            status = ExecutionStatus.DRAFT,
            createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        )
        executionRepository.save(execution)

        val executed = execute(execution, request.cases)
        return executionRepository.save(executed)
    }

    fun getExecution(executionId: String): Execution = executionRepository.findByIdOrNull(executionId)
        ?: throw ExecutionNotFoundException("Execution '$executionId' not found.")

    private fun execute(
        execution: Execution,
        cases: List<ExecutionRequest.Case>
    ): Execution {

        val problem = problemService.get(execution.problemId)
        val executionPrefix = "[${execution.executionId}]"

        val executionResultActual = getActualResult(problem, execution, cases)
        if (executionResultActual.status != ExecutionStatus.SUCCESS) {
            return execution.copy(
                status = executionResultActual.status.toExecutionStatus(),
                error = getErrorOrNull(executionResultActual)
            )
        }

        val executionResultExpected = getExpectedResultsIfNeeded(problem, execution, cases)
        if (executionResultExpected != null && executionResultExpected.status != ExecutionStatus.SUCCESS) {
            return execution.copy(
                status = ExecutionStatus.UNKNOWN_ERROR,
                error = "Failed to get expected results for test case. ${getErrorOrNull(executionResultExpected)}"
            )
        }

        val executedCases = cases.mapIndexed { i, case ->
            executionResultActual.invocations.getOrNull(i)
                ?.let {
                    val actual = it.stdOut?.lines()
                        ?.firstOrNull { line -> line.startsWith(executionPrefix) }
                        ?.removePrefix(executionPrefix)
                        .orEmpty()

                    val stdOut = it.stdOut?.lines()
                        ?.filterNot { line -> line.startsWith(executionPrefix) }
                        ?.joinToString("\n")

                    val stdErr = it.stdErr

                    val expected = case.expected
                        ?: let {
                            executionResultExpected?.invocations?.getOrNull(i)?.stdOut?.lines()
                                ?.firstOrNull { line -> line.startsWith(executionPrefix) }
                                ?.removePrefix(executionPrefix)
                                .orEmpty()
                        }

                    ExecutionCase(
                        status = if (actual == expected) ExecutionCaseStatus.PASSED else ExecutionCaseStatus.FAILED,
                        input = case.input,
                        expected = expected,
                        actual = actual,
                        stdOut = stdOut,
                        stdErr = stdErr?.takeIf { it.isNotEmpty() } ?: it.error,
                        runtime = it.details?.duration,
                        memory = it.details?.memory,
                    )
                }


        }
        execution.cases.addAll(executedCases.filterNotNull())

        if (executedCases.any { it == null }) {
            return execution.copy(
                status = ExecutionStatus.UNKNOWN_ERROR,
                error = "Failed to get actual result for one of the test cases."
            )
        }

        return execution.copy(
            status = if (execution.cases.all { it.status == ExecutionCaseStatus.PASSED }) ExecutionStatus.SUCCESS else ExecutionStatus.CASE_ERROR,
            avgMemory = execution.cases.mapNotNull { it.memory }.average(),
            avgRuntime = execution.cases.mapNotNull { it.runtime }.average()
        )
    }

    private fun getExpectedResultsIfNeeded(
        problem: Problem,
        execution: Execution,
        cases: List<ExecutionRequest.Case>,
    ): ExecutionResult? {
        if (cases.all { case -> case.expected != null })
            return null

        return with(problem.solution) {
            executionJobClient.call(
                io.codegeet.platform.common.ExecutionRequest(
                    code = buildExecutionCode(
                        snippet,
                        buildExecutionCall(problem, language),
                        language
                    ),
                    language = language,
                    invocations = cases
                        .map {
                            ExecutionJobRequest.InvocationRequest(
                                arguments = listOf(execution.executionId) + it.input.split("\n")
                            )
                        }
                ))
        }
    }

    private fun getActualResult(
        problem: Problem,
        execution: Execution,
        cases: List<ExecutionRequest.Case>,
    ) = executionJobClient.call(
        io.codegeet.platform.common.ExecutionRequest(
            code = buildExecutionCode(
                execution.snippet,
                buildExecutionCall(problem, execution.language),
                execution.language
            ),
            language = execution.language,
            invocations = cases.map {
                ExecutionJobRequest.InvocationRequest(
                    arguments = listOf(execution.executionId) + it.input.split("\n").orEmpty()
                )
            },
        )
    )

    private fun getErrorOrNull(actual: ExecutionResult) = (actual.error?.takeIf { it.isNotEmpty() }
        ?: actual.invocations
            .firstOrNull { it.status != InvocationStatus.SUCCESS }
            ?.let { invocation -> (invocation.stdErr?.takeIf { it.isNotEmpty() }) ?: invocation.error })

    private fun buildExecutionCall(problem: Problem, language: Language) =
        problem.snippets.first { it.language == language }.call

    private fun buildExecutionCode(snippet: String, call: String, language: Language) =
        languageTemplates[language]?.let { path ->
            readResource(path)
                .replace("{call}", call)
                .replace("{snippet}", snippet)
        } ?: throw LanguageNotSupportedException(language)

    fun readResource(path: String, charset: Charset = Charsets.UTF_8): String {
        val resource = ClassPathResource(path)
        return resource.inputStream.bufferedReader(charset).use { it.readText() }
    }

    private fun ExecutionStatus.toExecutionStatus(): ExecutionStatus = when (this) {
        ExecutionStatus.SUCCESS -> ExecutionStatus.SUCCESS
        ExecutionStatus.COMPILATION_ERROR -> ExecutionStatus.COMPILATION_ERROR
        ExecutionStatus.INVOCATION_ERROR -> ExecutionStatus.EXECUTION_ERROR
        ExecutionStatus.INTERNAL_ERROR -> ExecutionStatus.UNKNOWN_ERROR
        ExecutionStatus.TIMEOUT -> ExecutionStatus.TIMEOUT
    }
}
