package tororo1066.magicitemtransporter.data

class Result<T>(val value: T, val message: String? = null) {
    operator fun component1() = value
    operator fun component2() = message
}