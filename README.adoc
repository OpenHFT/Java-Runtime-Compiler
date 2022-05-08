= Chronicle Runtime Compiler
Chronicle Software
:css-signature: demo
:toc: macro
:toclevels: 2
:icons: font

image:https://maven-badges.herokuapp.com/maven-central/net.openhft/compiler/badge.svg[caption="",link=https://maven-badges.herokuapp.com/maven-central/net.openhft/compiler]
image:https://javadoc.io/badge2/net.openhft/compiler/javadoc.svg[link="https://www.javadoc.io/doc/net.openhft/compiler/latest/index.html"]
//image:https://javadoc-badge.appspot.com/net.openhft/compiler.svg?label=javadoc[JavaDoc, link=https://www.javadoc.io/doc/net.openhft/compiler]
image:https://img.shields.io/github/license/OpenHFT/Java-Runtime-Compiler[GitHub]
image:https://img.shields.io/badge/release%20notes-subscribe-brightgreen[link="https://chronicle.software/release-notes/"]
image:https://sonarcloud.io/api/project_badges/measure?project=OpenHFT_Java-Runtime-Compiler&metric=alert_status[link="https://sonarcloud.io/dashboard?id=OpenHFT_Java-Runtime-Compiler"]

This takes a String, compiles it and loads it returning you a class from what you built.
By default it uses the current ClassLoader.
It supports nested classes, otherwise builds one class at a time.

== On Maven Central

You can include in your project with

```xml
<dependency>
    <groupId>net.openhft</groupId>
    <artifactId>compiler</artifactId>
    <version><!-- The latest version (see above) --></version>
</dependency>
```

== Simple example

You need a CachedCompiler and access to your JDK's tools.jar.

```java
// dynamically you can call
String className = "mypackage.MyClass";
String javaCode = "package mypackage;\n" +
                 "public class MyClass implements Runnable {\n" +
                 "    public void run() {\n" +
                 "        System.out.println(\"Hello World\");\n" +
                 "    }\n" +
                 "}\n";
Class aClass = CompilerUtils.CACHED_COMPILER.loadFromJava(className, javaCode);
Runnable runner = (Runnable) aClass.newInstance();
runner.run();
```
     
I suggest making your class implement a KnownInterface of your choice as this will allow you to call/manipulate instances of you generated class.

Another more hacky way is to use this to override a class, provided it hasn't been loaded already.  
This means you can redefine an existing class and provide the methods and fields used match,
you have compiler redefine a class and code already compiled to use the class will still work.

== Using the CachedCompiler.

In this example, you can configure the compiler to write the files to a specific directory when you are in debug mode.
       
```java
private static final CachedCompiler JCC = CompilerUtils.DEBUGGING ?
                                                   new CachedCompiler(new File(parent, "src/test/java"), new File(parent, "target/compiled")) :
                                                   CompilerUtils.CACHED_COMPILER;
```
     
By selecting the src directory to match where your IDE looks for those files, it will allow your debugger to set into the code you have generated at runtime.

Note: you may need to delete these files if you want to regenerate them.
