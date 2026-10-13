package dotktr.probe

class Box<T>(val value: T) {
    fun identity(): T = value
}

fun sample(): String = Box("native PSI").identity()
