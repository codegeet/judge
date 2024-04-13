package io.codegeet.problems.exceptions

import io.codegeet.platform.common.language.Language

class LanguageNotSupportedException(language: Language) : RuntimeException("Language '$language' is not supported.")
