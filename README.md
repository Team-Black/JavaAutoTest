# JavaAutoTest
Java-based tool for unit test generating.

## Installation
1. Install JDK 8 (neither less, nor more);
2. Install Z3 Theorem Prover ();
3. All tests should pass, with the possible exception of the tests in the class *jbse.dec.DecisionProcedureTest* that require that you fix the path to the Z3 executable. You must modify line 46 and replace */opt/local/bin/z3* with your local path to the Z3 executable;
4. Run *Gradle -> Build*.
