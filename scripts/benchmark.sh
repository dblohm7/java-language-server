
#!/bin/bash

# Compile the benchmark
mvn test-compile

# Emit the dependencies classpath
mvn dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=scripts/classpath.txt

# Run the benchmark
java -cp $(cat scripts/classpath.txt):target/classes:target/test-classes --illegal-access=warn org.openjdk.jmh.Main BenchmarkParser

# Clean up
rm scripts/classpath.txt