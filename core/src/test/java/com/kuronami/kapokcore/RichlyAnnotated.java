package com.kuronami.kapokcore;

/**
 * A class carrying one of every annotation member shape the class file format allows, so
 * that a test can scan its real bytes with the loader's own visitor instead of guessing
 * what ASM hands over.
 *
 * <p>The default retention is {@code CLASS}, which is what almost every mod annotation
 * uses and is enough to be written into the class file - the scanner reads the bytes and
 * never loads the class, so nothing here needs to survive to runtime.
 */
@RichlyAnnotated.Everything(
        text = "alpha",
        number = 7,
        numbers = {1, 2, 3},
        longs = {4L},
        doubles = {1.5, 2.5},
        flags = {true, false},
        letters = {'a', 'b'},
        words = {"one", "two"},
        literal = String.class,
        primitiveLiteral = int.class,
        arrayLiteral = String[].class,
        colour = RichlyAnnotated.Colour.GREEN,
        colours = {RichlyAnnotated.Colour.RED, RichlyAnnotated.Colour.GREEN},
        nested = @RichlyAnnotated.Inner(depth = 2, tags = {"x"}),
        several = {@RichlyAnnotated.Inner(depth = 3), @RichlyAnnotated.Inner(depth = 4)})
class RichlyAnnotated {
    @Everything(text = "on a field", number = 1)
    String field;

    @Everything(text = "on a method", number = 2)
    void method() {
    }

    enum Colour {
        RED, GREEN
    }

    @interface Inner {
        int depth();

        String[] tags() default {};
    }

    @interface Everything {
        String text();

        int number();

        int[] numbers() default {};

        long[] longs() default {};

        double[] doubles() default {};

        boolean[] flags() default {};

        char[] letters() default {};

        String[] words() default {};

        Class<?> literal() default Object.class;

        Class<?> primitiveLiteral() default void.class;

        Class<?> arrayLiteral() default Object[].class;

        Colour colour() default Colour.RED;

        Colour[] colours() default {};

        Inner nested() default @Inner(depth = 0);

        Inner[] several() default {};
    }
}
