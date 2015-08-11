package eg;

import eg.components.BarImpl;
import eg.components.TeeImpl;
import eg.components.Foo;

public class FooBarTee{
    public final String name;
    public final TeeImpl tee;
    public final BarImpl bar;
    public final BarImpl copy;
    public final Foo foo;

    public FooBarTee(String name) {
        // when viewing this file, ensure it is synchronised with the copy on disk.
        System.out.println("generated test Tue Aug 11 07:09:54 BST 2015");
        this.name = name;

        tee = new TeeImpl("test");

        bar = new BarImpl(tee, 55);

        copy = new BarImpl(tee, 555);

        // you should see the current date here after synchronisation.
        foo = new Foo(bar, copy, "generated test Tue Aug 11 07:09:54 BST 2015", 5);
    }

    public void start() {
    }

    public void stop() {
    }

    public void close() {
        stop();

    }
}
