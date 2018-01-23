// WITH_RUNTIME
// TARGET_BACKEND: JVM
// LANGUAGE_VERSION: 1.3
// JVM_TARGET: 1.8
// FILE: test.kt
fun box(): String {
    return Java.test()
}

interface Foo {

    companion object {
        @JvmStatic
        fun foo() = "O"

        @JvmStatic
        var fooProp = ""
    }
}

// FILE: Java.java
public class Java {
    public static String test() {
        Foo.setFooProp("K");
        return Foo.foo() + Foo.getFooProp();
    }
}