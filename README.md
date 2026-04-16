# Project Overview

This project is a mutation testing tool built on top of **MuJava** and extensively customized to support the following functionalities:

## 1. Mutant Generation
After enhancement, the mutant generation module now supports **Java 8**.  
The entry point is located in the `mujava/cmd` directory:

- `MutantsGenerator.java`

## 2. Test Case Generation Module
A test case generation module has been added, with support for different testing frameworks such as **JUnit 4**.  
The entry point is located in the `mujava/testgenerator` directory:

- `EvoSuiteTestGenerator.java`

## 3. Test Case Cleaning Module
A test case cleaning module has been added, supporting **JUnit 3**, **JUnit 4**, and **JUnit 5**.  
The entry point is located in the `mujava/testgenerator` directory:

- `EvoSuiteCleaner.java`

## 4. Mutant Execution and Testing Module
A mutant execution and testing module has been added, supporting **JUnit 3**, **JUnit 4**, and **JUnit 5**.  
It also supports **process isolation**, which prevents the original program and mutant dependencies from interfering with or contaminating each other during execution.  
The entry point is located in the `mujava/cmd` directory:

- `TestRunner_MultiProcess_batched.java`

## Note
All of the above functionalities are intended to be executed within a **Maven-based project structure**, and they rely on the complete set of dependencies declared in `pom.xml`.