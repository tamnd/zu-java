# There is no source here, and no documentation either

`zudb-native` is a jar of shared libraries. Nothing in it is compiled from Java and nothing in it has an API, so a sources jar and a javadoc jar have nothing to hold.

Maven Central requires both of them beside every published jar, and there is no way to say that one of them is meaningless for an artifact. So both are published and both contain this file, which is at least an answer to whoever opens one looking for something.

The source for the libraries is the engine, at https://github.com/tamnd/zu. The API this artifact serves is `dev.zudb`, whose javadoc is published beside the `zudb` artifact.
