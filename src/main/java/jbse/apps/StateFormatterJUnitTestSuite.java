package jbse.apps;

import static jbse.common.Type.getArrayMemberType;
import static jbse.common.Type.className;
import static jbse.common.Type.isArray;
import static jbse.common.Type.isReference;
import static jbse.common.Type.isPrimitive;
import static jbse.common.Type.isPrimitiveFloating;
import static jbse.common.Type.isPrimitiveIntegral;
import static jbse.common.Type.splitParametersDescriptors;
import static jbse.common.Type.splitReturnValueDescriptor;

import java.util.*;
import java.util.function.Supplier;

import jbse.common.Type;
import jbse.common.exc.UnexpectedInternalException;
import jbse.mem.*;
import jbse.mem.exc.FrozenStateException;
import jbse.mem.exc.ThreadStackEmptyException;
import jbse.val.Any;
import jbse.val.Expression;
import jbse.val.PrimitiveSymbolicApply;
import jbse.val.PrimitiveSymbolicAtomic;
import jbse.val.NarrowingConversion;
import jbse.val.Primitive;
import jbse.val.PrimitiveSymbolic;
import jbse.val.PrimitiveVisitor;
import jbse.val.Reference;
import jbse.val.ReferenceSymbolic;
import jbse.val.Simplex;
import jbse.val.Symbolic;
import jbse.val.Term;
import jbse.val.Value;
import jbse.val.WideningConversion;

/**
 * A {@link Formatter} that emits a JUnit test suite, with 
 * test cases covering the symbolic states.
 * 
 * @author Esther Turati
 * @author Pietro Braione
 */
public final class StateFormatterJUnitTestSuite implements Formatter {
    private final Supplier<State> initialStateSupplier;
    private final Supplier<Map<PrimitiveSymbolic, Simplex>> modelSupplier;
    private StringBuilder output = new StringBuilder();
    private int testCounter = 0;

    public StateFormatterJUnitTestSuite(Supplier<State> initialStateSupplier, 
                                        Supplier<Map<PrimitiveSymbolic, Simplex>> modelSupplier) {
        this.initialStateSupplier = initialStateSupplier;
        this.modelSupplier = modelSupplier;
    }

    @Override
    public void formatPrologue(final String clazz) {
        this.output.append("import org.junit.Test;\n")
                .append("import static org.junit.Assert.*;\n")
                .append("import java.lang.*;\n")
                .append("import java.util.*;\n").append('\n')
                .append("public class ")
                .append(clazz).append("Test {\n");

        /*this.initialStateSupplier.get().getRootClass().getClassName();
        this.output.append(PROLOGUE);*/
    }

    @Override
    public void formatState(State state) {
        try {
			new JUnitTestCase(this.output, this.initialStateSupplier.get(), state, this.modelSupplier.get(), this.testCounter++);
		} catch (FrozenStateException e) {
			this.output.delete(0, this.output.length());
		}
    }

    @Override
    public void formatEpilogue() {
        this.output.append("}\n");
    }

    @Override
    public String emit() {
        return this.output.toString();
    }

    @Override
    public void cleanup() {
        this.output = new StringBuilder();
    }

    private static class JUnitTestCase {
        private static final String INDENT = "        ";
        private final StringBuilder s; 
        private final HashMap<String, String> symbolsToVariables = new HashMap<>();
        private boolean panic = false;

        JUnitTestCase(StringBuilder s, State initialState, State finalState, Map<PrimitiveSymbolic, Simplex> model, int testCounter) 
        throws FrozenStateException {
            this.s = s;
            appendMethodDeclaration(finalState, testCounter);
            appendInputsInitialization(finalState, model);
            appendInvocationOfMethodUnderTest(initialState, finalState);
            appendAssert(initialState, finalState);
            appendMethodEnd(finalState, testCounter);
        }

        private void appendMethodDeclaration(State finalState, int testCounter) throws FrozenStateException {
            if (this.panic) {
                return;
            }
            final Reference exception = finalState.getStuckException();
            if (exception == null) {
                this.s.append("    @Test\n");
                this.s.append("    public void test");
                this.s.append(testCounter);
            } else {
                final String exceptionTest = javaClass(finalState.getObject(exception).getType().getClassName());
                this.s.append("    @Test(expected=");
                this.s.append(exceptionTest);
                this.s.append(".class)\n");
                this.s.append("    public void test");
                this.s.append(exceptionTest.substring(exceptionTest.lastIndexOf(".") + 1));
            }
            this.s.append("() {\n").append('\n');
        }

        private void appendInputsInitialization(State finalState, Map<PrimitiveSymbolic, Simplex> model)
                throws FrozenStateException {
            if (this.panic) {
                return;
            }
            final Collection<Clause> pathCondition = finalState.getPathCondition();
            for (Iterator<Clause> iterator = pathCondition.iterator(); iterator.hasNext(); ) {
                final Clause clause = iterator.next();
                if (clause.toString().contains("pre_init")) {
                    continue;
                }
                this.s.append(INDENT);
                if (clause instanceof ClauseAssumeExpands) {
                    final ClauseAssumeExpands clauseExpands = (ClauseAssumeExpands) clause;
                    final Symbolic symbol = clauseExpands.getReference();
                    final long heapPosition = clauseExpands.getHeapPosition();
                    setWithNewObject(finalState, symbol, heapPosition, iterator, model);
                } else if (clause instanceof ClauseAssumeNull) {
                    final ClauseAssumeNull clauseNull = (ClauseAssumeNull) clause;
                    final ReferenceSymbolic symbol = clauseNull.getReference();
                    setWithNull(symbol);
                } else if (clause instanceof ClauseAssumeAliases) {
                    final ClauseAssumeAliases clauseAliases = (ClauseAssumeAliases) clause;
                    final Symbolic symbol = clauseAliases.getReference();
                    final long heapPosition = clauseAliases.getHeapPosition();
                    setWithAlias(finalState, symbol, heapPosition);
                } else if (clause instanceof ClauseAssume) {
                    if (model == null) {
                        this.panic = true;
                        return;
                    }
                    final ClauseAssume clauseAssume = (ClauseAssume) clause;
                    final Primitive p = clauseAssume.getCondition();
                    addPrimitiveSymbolAssignments(p, model);
                }
                this.s.append("\n");
            }
        }

        private void appendInvocationOfMethodUnderTest(State initialState, State finalState) 
        throws FrozenStateException {
            if (this.panic) {
                return;
            }
            final Value returnedValue = finalState.getStuckReturn();
            final boolean mustCheckReturnedValue = 
                (returnedValue != null)  && (isPrimitive(returnedValue.getType()) || returnedValue instanceof Symbolic);
            this.s.append(INDENT);
            try {
                if (mustCheckReturnedValue) {
                    final char returnType = Objects.requireNonNull(splitReturnValueDescriptor(initialState.getRootMethodSignature().getDescriptor())).charAt(0);
                    switch (returnType){
                        case Type.BOOLEAN:
                            this.s.append("boolean");
                            break;
                        case Type.BYTE:
                            this.s.append("byte");
                            break;
                        case Type.CHAR:
                            this.s.append("char");
                            break;
                        case Type.SHORT:
                            this.s.append("short");
                            break;
                        case Type.INT:
                            this.s.append("int");
                            break;
                        case Type.FLOAT:
                            this.s.append("float");
                            break;
                        case Type.LONG:
                            this.s.append("long");
                            break;
                        case Type.DOUBLE:
                            this.s.append("double");
                            break;
                        default:
                            final Reference returnedRef = (Reference) returnedValue;
                            if (finalState.isNull(returnedRef)) {
                                this.s.append("java.lang.Object");
                            } else {
                                final String javaClazz = javaClass(finalState.getObject(returnedRef).getType().getClassName());
                                this.s.append(javaClazz.substring(javaClazz.lastIndexOf('.') + 1));
                            }
                            break;
                    }
                    this.s.append(" returnedValue = ");
                }
                final String methodName = initialState.getRootMethodSignature().getName();
                this.s.append(methodName);
                this.s.append('(');
                final Map<Integer, Variable> lva = initialState.getRootFrame().localVariables();
                final TreeSet<Integer> slots = new TreeSet<>(lva.keySet());
                final int numParamsExcludedThis = splitParametersDescriptors(initialState.getRootMethodSignature().getDescriptor()).length;
                int currentParam = 1;
                for (int slot : slots) {
                    final Variable lv = lva.get(slot);
                    if ("this".equals(lv.getName())) {
                        continue;
                    }
                    if (currentParam > numParamsExcludedThis) {
                        break;
                    }
                    if (currentParam > 1) {
                        s.append(", ");
                    }
                    final String variable = lv.getName();
                    if (this.symbolsToVariables.containsValue(variable)) {
                        this.s.append(variable);
                    } else if (isPrimitiveIntegral(lv.getType().charAt(0))) {
                        this.s.append('0');
                    } else if (isPrimitiveFloating(lv.getType().charAt(0))) {
                        this.s.append("0.0f");
                    } else {
                        this.s.append("null");
                    }
                    ++currentParam;
                }
                this.s.append(");\n");
            } catch (ThreadStackEmptyException e) {
                //this should never happen
                throw new UnexpectedInternalException(e);
            }
        }

        private void appendAssert(State initialState, State finalState) throws FrozenStateException {
            if (this.panic) {
                return;
            }
            final Value returnedValue = finalState.getStuckReturn();
            final boolean mustCheckReturnedValue =
                    (returnedValue != null)  && (isPrimitive(returnedValue.getType()) || returnedValue instanceof Symbolic);
            if (mustCheckReturnedValue) {
                this.s.append(INDENT);
                this.s.append("assertTrue(returnedValue == ");
                final char returnType;
                try {
                    returnType = Objects.requireNonNull(splitReturnValueDescriptor(initialState.getRootMethodSignature().getDescriptor())).charAt(0);
                } catch (ThreadStackEmptyException e) {
                    //this should never happen
                    throw new UnexpectedInternalException(e);
                }
                if (returnType == Type.BOOLEAN) {
                    if (returnedValue instanceof Simplex) {
                        final Simplex returnedValueSimplex = (Simplex) returnedValue;
                        this.s.append(returnedValueSimplex.isZeroOne(true) ? "false" : "true");
                    } else {
                        this.s.append(returnedValue.toString());
                    }
                } else if (isPrimitive(returnType)) {
                    if (returnedValue instanceof Simplex) {
                        switch (returnType) {
                            case Type.BYTE:
                                this.s.append("(byte) ");
                                break;
                            case Type.CHAR:
                                this.s.append("(char) ");
                                break;
                            case Type.SHORT:
                                this.s.append("(short) ");
                                break;
                            case Type.INT:
                                this.s.append("(int) ");
                                break;
                            case Type.FLOAT:
                                this.s.append("(float) ");
                                break;
                            case Type.LONG:
                                this.s.append("(long) ");
                                break;
                            case Type.DOUBLE:
                                this.s.append("(double) ");
                                break;
                            default:
                                break;
                        }
                    }
                    if (returnedValue instanceof Expression) {
                        this.s.append(((Expression) returnedValue).toStringWithValuesNames(this.symbolsToVariables));
                    } else {
                        // this function is missing variables name
                        this.s.append(returnedValue.toString());
                    }
                } else {
                    final Reference returnedRef = (Reference) returnedValue;
                    if (finalState.isNull(returnedRef)) {
                        this.s.append("null");
                    } else {
                        final String var = generateName(finalState.getObject(returnedRef).getOrigin().asOriginString());
                        if (hasMemberAccessor(var)) {
                            this.s.append(getValue(var));
                        } else {
                            this.s.append(var);
                        }
                    }
                }
                this.s.append(");\n");
            }
        }

        private void appendMethodEnd(State finalState, int testCounter) {
            if (this.panic) {
                this.s.delete(0, s.length());
                this.s.append("    //Unable to generate test case ");
                this.s.append(testCounter);
                this.s.append(" for state ");
                this.s.append(finalState.getBranchIdentifier());
                this.s.append('[');
                this.s.append(finalState.getSequenceNumber());
                this.s.append("] (no numeric solution from the solver)\n");
            } else {
                this.s.append("    }\n");
            }
        }

        private void setWithNewObject(State finalState, Symbolic symbol, long heapPosition, 
                                      Iterator<Clause> iterator, Map<PrimitiveSymbolic, Simplex> model) 
        throws FrozenStateException {        
            makeVariableFor(symbol);
            final String var = getVariableFor(symbol);
            final String type = getTypeOfObjectInHeap(finalState, heapPosition);
            final String instantiationStmt;
            if (!"this".equals(var)) {
                if (isArray(type)) {
                    //the next clause predicates on the array length
                    ClauseAssume clauseLength = (ClauseAssume) iterator.next();
                    final Simplex length = arrayLength(clauseLength, model);
                    instantiationStmt = "new " + javaType(Objects.requireNonNull(getArrayMemberType(type)).
                            substring(Objects.requireNonNull(getArrayMemberType(type)).lastIndexOf(".") + 1)) + " [" + length.toString() + "]";
                } else {
                    instantiationStmt = "new " + javaType(type.substring(type.lastIndexOf(".") + 1)) + "()";
                }
                final String className = javaClass(type.substring(type.lastIndexOf("$") + 1));
                if (hasMemberAccessor(var)){
                    setByReflection(var, instantiationStmt);
                } else if (hasArrayAccessor(var)) {
                    this.s.append(var);
                    this.s.append(" = ");
                    this.s.append(instantiationStmt);
                    this.s.append(";");
                } else {
                    this.s.append(className);
                    this.s.append(' ');
                    this.s.append(var);
                    this.s.append(" = ");
                    this.s.append(instantiationStmt);
                    this.s.append(";");
                }
            }
        }

        private void setWithNull(ReferenceSymbolic symbol) {
            makeVariableFor(symbol);
            final String var = getVariableFor(symbol);
            if (hasMemberAccessor(var)) {
                setByReflection(var, "null");
            } else if (hasArrayAccessor(var)) {
                this.s.append(var);
                this.s.append(" = null;");
            } else {
                final String type = javaClass(symbol.getStaticType());
                this.s.append(type.substring(type.lastIndexOf("$") + 1));
                this.s.append(' ');
                this.s.append(var);
                this.s.append(" = null;");
            }
        }

        private void setWithAlias(State finalState, Symbolic symbol, long heapPosition) 
        throws FrozenStateException {
            makeVariableFor(symbol);
            final String var = getVariableFor(symbol);
            final String value = getValue(getOriginOfObjectInHeap(finalState, heapPosition));
            if (hasMemberAccessor(var)) {
                setByReflection(var, value);
            } else if (hasArrayAccessor(var)) {
                this.s.append(var);
                this.s.append(" = "); 
                this.s.append(value);
                this.s.append(';'); 
            } else {
                final String type = javaClass(getTypeOfObjectInHeap(finalState, heapPosition));
                this.s.append(type.substring(type.lastIndexOf(".") + 1));
                this.s.append(' '); 
                this.s.append(var); 
                this.s.append(" = "); 
                this.s.append(value);
                this.s.append(';'); 
            }
        }

        private Simplex arrayLength(ClauseAssume clause, Map<PrimitiveSymbolic, Simplex> model) {
            //the clause has shape {length} >= 0 - i.e., it has just
            //one symbol, the length
            final Set<PrimitiveSymbolic> symbols = primitiveSymbolsIn(clause.getCondition());
            final PrimitiveSymbolic symbol = symbols.iterator().next();
            makeVariableFor(symbol); //so it remembers that the symbol has been processed
            final Simplex value = model.get(symbol);
            if (value == null) {
                //this should never happen
                throw new UnexpectedInternalException("No value found in model for symbol " + symbol.toString() + ".");
            }
            return value;
        }

        private String javaType(String s){
            if (s == null) {
                return null;
            }
            final String a = s.replace('/', '.');
            return (isReference(a) ? className(a) : a);
        }

        private String javaClass(String type){
            final String s = javaType(type).replace('$', '.');
            final char[] tmp = s.toCharArray();
            int arrayNestingLevel = 0;
            boolean hasReference = false;
            int start = 0;
            for (int i = 0; i < tmp.length ; ++i) {
                if (tmp[i] == '[') {
                    ++arrayNestingLevel;
                } else if (tmp[i] == 'L') {
                    hasReference = true;
                } else {
                    start = i;
                    break;
                }
            }
            final StringBuilder retVal = new StringBuilder(s.substring(start, (hasReference ? tmp.length - 1 : tmp.length)));
            for (int k = 1; k <= arrayNestingLevel; ++k) {
                retVal.append("[]");
            }
            return retVal.toString();
        }

        private String generateName(String name) {
            return name.replace("{ROOT}:", "");
        }

        private void makeVariableFor(Symbolic symbol) {
            final String value = symbol.getValue(); 
            final String origin = symbol.asOriginString();
            if (!this.symbolsToVariables.containsKey(value)) {
                this.symbolsToVariables.put(value, generateName(origin));
            }
        }

        private String getVariableFor(Symbolic symbol) {
            final String value = symbol.getValue(); 
            return this.symbolsToVariables.get(value);
        }

        private static String getTypeOfObjectInHeap(State finalState, long num) 
        throws FrozenStateException {
            final Map<Long, Objekt> heap = finalState.getHeap();
            final Objekt o = heap.get(num);
            return o.getType().getClassName();
        }

        private String getOriginOfObjectInHeap(State finalState, long heapPos){
            //TODO extract this code and share with DecisionProcedureAlgorithms.getPossibleAliases
            final Collection<Clause> path = finalState.getPathCondition();
            for (Clause clause : path) {
                if (clause instanceof ClauseAssumeExpands) { // == Obj fresh
                    final ClauseAssumeExpands clauseExpands = (ClauseAssumeExpands) clause;
                    final long heapPosCurrent = clauseExpands.getHeapPosition();
                    if (heapPosCurrent == heapPos) {
                        return getVariableFor(clauseExpands.getReference());
                    }
                }
            }
            return null;
        }

        private boolean hasMemberAccessor(String s) {
            return (s.indexOf('.') != -1);
        }

        private boolean hasArrayAccessor(String s) {
            return (s.indexOf('[') != -1);
        }

        private String replaceAccessorsWithGetters(String container, String accessExpression) {
            StringBuilder a = new StringBuilder(container);
            String s = accessExpression;    
            if (hasMemberAccessor(s)) {
                s = s.substring(s.indexOf('.') + 1);
            } else { 
                return a.toString();
            }

            while (s != null && s.length() > 0) {
                if (hasMemberAccessor(s)){
                    int i = s.indexOf('.');
                    a.append(".get(\"").append(s, 0, i).append("\")");
                    s = s.substring(i + 1);
                } else {
                    a.append(".get(\"").append(s).append("\")");
                    s = null;
                }            
            }
            return a.toString();
        }

        private String getValue(String accessExpression) {
            if (hasMemberAccessor(accessExpression)) {
                final String container = accessExpression.substring(0, accessExpression.indexOf('.'));
                final String accessExpressionWithGetters = replaceAccessorsWithGetters(container, accessExpression);
                return accessExpressionWithGetters + ".getValue()" ;
            } else {
                return accessExpression;
            }
        }

        private void setByReflection(String accessExpression, String value) {
            final String container = accessExpression.substring(0, accessExpression.indexOf('.'));
            final String accessExpressionWithGetters = replaceAccessorsWithGetters(container, accessExpression.substring(0, accessExpression.lastIndexOf('.')));
            final String fieldToSet = accessExpression.substring(accessExpression.lastIndexOf('$') + 1);
            this.s.append(accessExpressionWithGetters);
            this.s.append(".set(\"");
            this.s.append(fieldToSet);
            this.s.append("\", ");
            this.s.append(value);
            this.s.append(");");
        }
        
        private void addPrimitiveSymbolAssignments(Primitive e, Map<PrimitiveSymbolic, Simplex> model) {
            final Set<PrimitiveSymbolic> symbols = primitiveSymbolsIn(e);
            for (PrimitiveSymbolic symbol : symbols) {
                if (getVariableFor(symbol) == null) { //not yet done
                    final Simplex value = model.get(symbol);
                    if (value == null) {
                        //this should never happen
                        throw new UnexpectedInternalException("No value found in model for symbol " + symbol.toString() + ".");
                    }
                    setWithNumericValue(symbol, value);
                }
            }
        }
        
        private Set<PrimitiveSymbolic> primitiveSymbolsIn(Primitive e) {
            final HashSet<PrimitiveSymbolic> symbols = new HashSet<>();
            PrimitiveVisitor v = new PrimitiveVisitor() {

                @Override
                public void visitWideningConversion(WideningConversion x) throws Exception {
                    x.getArg().accept(this);
                }

                @Override
                public void visitTerm(Term x) throws Exception { }

                @Override
                public void visitSimplex(Simplex x) throws Exception { }

                @Override
                public void visitPrimitiveSymbolicAtomic(PrimitiveSymbolicAtomic s) {
                    symbols.add(s);
                }

                @Override
                public void visitNarrowingConversion(NarrowingConversion x) throws Exception {
                    x.getArg().accept(this);
                }

                @Override
                public void visitPrimitiveSymbolicApply(PrimitiveSymbolicApply x) throws Exception {
                    for (Value v : x.getArgs()) {
                        if (v instanceof Primitive) {
                            ((Primitive) v).accept(this);
                        }
                    }
                }

                @Override
                public void visitExpression(Expression e) throws Exception {
                    if (e.isUnary()) {
                        e.getOperand().accept(this);
                    } else {
                        e.getFirstOperand().accept(this);
                        e.getSecondOperand().accept(this);
                    }
                }

                @Override
                public void visitAny(Any x) { }
            };

            try {
                e.accept(v);
            } catch (Exception exc) {
                //this should never happen
                throw new AssertionError(exc);
            }
            return symbols;
        }

        private void setWithNumericValue(PrimitiveSymbolic symbol, Simplex value) {
            final boolean variableNotYetCreated = (getVariableFor(symbol) == null);
            if (variableNotYetCreated) {
                makeVariableFor(symbol);
            }
            final String var = getVariableFor(symbol);
            if (hasMemberAccessor(var)) {
                switch (symbol.getType()){
                    case Type.BOOLEAN:
                        setByReflection(var, "(" + value.toString() + " != 0)");
                        break;
                    case Type.BYTE:
                        setByReflection(var, "(byte) " + value.toString());
                        break;
                    case Type.CHAR:
                        setByReflection(var, "(char) " + value.toString());
                        break;
                    case Type.SHORT:
                        setByReflection(var, "(short) " + value.toString());
                        break;
                    case Type.INT:
                        setByReflection(var, "(int) " + value.toString());
                        break;
                    case Type.FLOAT:
                        setByReflection(var, "(float) " + value.toString());
                        break;
                    case Type.LONG:
                        setByReflection(var, "(long) " + value.toString());
                        break;
                    case Type.DOUBLE:
                        setByReflection(var, "(double) " + value.toString());
                        break;
                    default:
                        setByReflection(var, value.toString());
                        break;
                }
            } else {
                this.s.append("long ");
                this.s.append(var);
                this.s.append(" = ").append(value.toString()).append(";");
            }
        }
    }
}
