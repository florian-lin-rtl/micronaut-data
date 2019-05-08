package io.micronaut.data.processor.visitors.finders;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.data.model.Association;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.query.AssociationQuery;
import io.micronaut.data.model.query.Query;
import io.micronaut.data.model.query.Sort;
import io.micronaut.data.processor.model.SourcePersistentEntity;
import io.micronaut.data.processor.model.SourcePersistentProperty;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Abstract base class for dynamic finders. This class is designed to be used only within the compiler
 *
 * @author Graeme Rocher
 * @since 1.0
 */
abstract class DynamicFinder extends AbstractPatternBasedMethod implements PredatorMethodCandidate {

    public static final String OPERATOR_OR = "Or";
    public static final String OPERATOR_AND = "And";
    public static final String[] OPERATORS = {OPERATOR_AND, OPERATOR_OR};
    private static Pattern methodExpressionPattern;
    private static final String NOT = "Not";
    private static final Map<String, Constructor> methodExpressions = new LinkedHashMap<>();

    static {
        // populate the default method expressions
        try {
            Class[] classes = {
                    CriterionMethodExpression.Equal.class, CriterionMethodExpression.NotEqual.class, CriterionMethodExpression.NotInList.class, CriterionMethodExpression.InList.class, CriterionMethodExpression.InRange.class, CriterionMethodExpression.Between.class, CriterionMethodExpression.Like.class, CriterionMethodExpression.Ilike.class, CriterionMethodExpression.Rlike.class,
                    CriterionMethodExpression.GreaterThanEquals.class, CriterionMethodExpression.LessThanEquals.class, CriterionMethodExpression.GreaterThan.class,
                    CriterionMethodExpression.LessThan.class, CriterionMethodExpression.IsNull.class, CriterionMethodExpression.IsNotNull.class, CriterionMethodExpression.IsEmpty.class,
                    CriterionMethodExpression.IsEmpty.class, CriterionMethodExpression.IsNotEmpty.class};
            Class[] constructorParamTypes = {String.class};
            for (Class c : classes) {
                methodExpressions.put(c.getSimpleName(), c.getConstructor(constructorParamTypes));
            }
        } catch (SecurityException e) {
            // ignore
        } catch (NoSuchMethodException e) {
            // ignore
        }

        resetMethodExpressionPattern();

    }

    private Pattern[] operatorPatterns;
    private String[] operators;
    private Pattern orderByPattern = Pattern.compile("(.+)OrderBy([\\w\\d]+)");

    protected DynamicFinder(final String... prefixes) {
        this(compilePattern(prefixes), OPERATORS);
    }

    protected DynamicFinder(final String[] prefixes, final String[] operators) {
        this(compilePattern(prefixes), operators);
    }

    protected DynamicFinder(final Pattern pattern) {
        this(pattern, OPERATORS);
    }

    protected DynamicFinder(final Pattern pattern, final String[] operators) {
        super(pattern);
        this.operators = operators;
        this.operatorPatterns = new Pattern[operators.length];
        populateOperators(operators);
    }

    private static Pattern compilePattern(String[] prefixes) {
        if (ArrayUtils.isEmpty(prefixes)) {
            throw new IllegalArgumentException("At least one prefix required");
        }
        String prefixPattern = String.join("|", prefixes);
        String patternStr = "((" + prefixPattern + ")([\\w\\d]*?)By)([A-Z]\\w*)";
        return Pattern.compile(patternStr);
    }

    @Override
    public PredatorMethodInfo buildMatchInfo(@Nonnull MethodMatchContext matchContext) {
        List<CriterionMethodExpression> expressions = new ArrayList<>();
        List<ProjectionMethodExpression> projectionExpressions = new ArrayList<>();
        ParameterElement[] parameters = matchContext.getParameters();
        MethodElement methodElement = matchContext.getMethodElement();
        String methodName = methodElement.getName();
        SourcePersistentEntity entity = matchContext.getEntity();
        VisitorContext visitorContext = matchContext.getVisitorContext();
        Matcher match = pattern.matcher(methodName);
        // find match
        match.find();

        String[] queryParameters;
        int totalRequiredArguments = 0;
        // get the sequence clauses
        List<Sort.Order> orderList = new ArrayList<>();
        final String querySequence = matchOrder(match.group(4), orderList);
        String projectionSequence = match.group(3);

        // if it contains operator and split
        boolean containsOperator = false;
        String operatorInUse = null;
        if (querySequence != null) {
            for (int i = 0; i < operators.length; i++) {
                Matcher currentMatcher = operatorPatterns[i].matcher(querySequence);
                if (currentMatcher.find()) {
                    containsOperator = true;
                    operatorInUse = operators[i];

                    queryParameters = querySequence.split(operatorInUse);

                    // loop through query parameters and create expressions
                    // calculating the number of arguments required for the expression
                    int argumentCursor = 0;
                    for (String queryParameter : queryParameters) {
                        CriterionMethodExpression currentExpression = findMethodExpression(entity, queryParameter);
                        final int requiredArgs = currentExpression.getArgumentsRequired();
                        // populate the arguments into the Expression from the argument list
                        String[] currentArguments = new String[requiredArgs];
                        if ((argumentCursor + requiredArgs) > parameters.length) {
                            visitorContext.fail("Insufficient arguments to method", methodElement);
                            return null;
                        }

                        for (int k = 0; k < requiredArgs; k++, argumentCursor++) {
                            ParameterElement argument = parameters[argumentCursor];
                            currentArguments[k] = argument.getName();
                        }
                        initializeExpression(currentExpression, currentArguments);


                        // add to list of expressions
                        totalRequiredArguments += currentExpression.argumentsRequired;
                        expressions.add(currentExpression);
                    }
                    break;
                }
            }
        }

        if (StringUtils.isNotEmpty(projectionSequence)) {
            boolean processedThroughOperator = false;
            for (int i = 0; i < operators.length; i++) {
                Matcher currentMatcher = operatorPatterns[i].matcher(projectionSequence);
                if (currentMatcher.find()) {
                    processedThroughOperator = true;
                    String[] projections = projectionSequence.split(operators[i]);

                    // loop through query parameters and create expressions
                    // calculating the number of arguments required for the expression
                    for (String projection : projections) {
                        ProjectionMethodExpression currentExpression = ProjectionMethodExpression.matchProjection(
                                matchContext,
                                projection
                        );

                        if (currentExpression != null) {
                            // add to list of expressions
                            projectionExpressions.add(currentExpression);
                        }

                    }
                    break;
                }
            }

            if (!processedThroughOperator) {
                ProjectionMethodExpression currentExpression = ProjectionMethodExpression.matchProjection(
                        matchContext,
                        projectionSequence
                );

                if (currentExpression != null) {
                    // add to list of expressions
                    projectionExpressions.add(currentExpression);
                }
            }
        }

        // otherwise there is only one expression
        if (!containsOperator && querySequence != null) {
            CriterionMethodExpression solo = findMethodExpression(entity, querySequence);


            final int requiredArguments = solo.getArgumentsRequired();
            if (requiredArguments > parameters.length) {
                visitorContext.fail("Insufficient arguments to method", methodElement);
                return null;
            }

            totalRequiredArguments += requiredArguments;
            String[] soloArgs = new String[requiredArguments];
            for (int i = 0; i < soloArgs.length; i++) {
                soloArgs[i] = parameters[i].getName();
            }
            initializeExpression(solo, soloArgs);
            expressions.add(solo);
        }

        // if the total of all the arguments necessary does not equal the number of arguments
        // throw exception
        if (totalRequiredArguments > parameters.length) {
            visitorContext.fail("Insufficient arguments to method", methodElement);
            return null;
        }

        Query query = Query.from(entity);
        ClassElement queryResultType = entity.getClassElement();

        for (Sort.Order order : orderList) {
            String prop = order.getProperty();
            SourcePersistentProperty p = entity.getPropertyByName(prop);
            if (p == null) {
                visitorContext.fail("Cannot order by non-existent property: " + prop, methodElement);
                return null;
            } else {
                query.order(order);
            }
        }

        if (CollectionUtils.isNotEmpty(projectionExpressions)) {

            if (projectionExpressions.size() == 1) {
                // only one projection so the return type should match the project result type
                ProjectionMethodExpression projection = projectionExpressions.get(0);
                queryResultType = projection.getExpectedResultType();
                projection.apply(matchContext, query);
            } else {
                for (ProjectionMethodExpression projectionExpression : projectionExpressions) {
                    projectionExpression.apply(matchContext, query);
                }
            }
        }

        if ("Or".equalsIgnoreCase(operatorInUse)) {
            Query.Disjunction disjunction = new Query.Disjunction();
            for (CriterionMethodExpression expression : expressions) {
                disjunction.add(expression.createCriterion());
            }

            query.add(disjunction);
        } else {
            for (CriterionMethodExpression expression : expressions) {
                Query.Criterion criterion = expression.createCriterion();
                query.add(criterion);
            }
        }

        return buildInfo(
                matchContext,
                queryResultType,
                query
        );
    }

    private String matchOrder(String querySequence, List<Sort.Order> orders) {
        if (orderByPattern.matcher(querySequence).matches()) {

            Matcher matcher = orderByPattern.matcher(querySequence);
            StringBuffer buffer = new StringBuffer();
            while(matcher.find()) {
                matcher.appendReplacement(buffer, "$1");
                String orderDef = matcher.group(2);
                if (StringUtils.isNotEmpty(orderDef)) {
                    String prop = NameUtils.decapitalize(orderDef);
                    if (prop.endsWith("Desc")) {
                        orders.add(Sort.Order.desc(prop.substring(0, prop.length() - 4)));
                    } else if (prop.equalsIgnoreCase("Asc")) {
                        orders.add(Sort.Order.asc(prop.substring(0, prop.length() - 3)));
                    } else {
                        orders.add(Sort.Order.asc(prop));
                    }
                }
            }
            matcher.appendTail(buffer);
            return buffer.toString();
        }
        return querySequence;
    }

    /**
     * Build the method info
     *
     * @param matchContext    The method match context
     * @param queryResultType The query result type
     * @param query           The query
     * @return The method info
     */
    protected abstract @Nullable
    PredatorMethodInfo buildInfo(
            @NonNull MethodMatchContext matchContext,
            @NonNull ClassElement queryResultType,
            @Nullable Query query
    );

    /**
     * Checks whether the given method is a match
     *
     * @param methodElement The method element
     * @return True if it is
     */
    @Override
    public boolean isMethodMatch(MethodElement methodElement) {
        String methodName = methodElement.getName();
        return pattern.matcher(methodName.subSequence(0, methodName.length())).find();
    }

    private void initializeExpression(CriterionMethodExpression currentExpression, String[] currentArguments) {
        currentExpression.setArgumentNames(currentArguments);
    }

    protected static CriterionMethodExpression findMethodExpression(SourcePersistentEntity entity, String expression) {
        CriterionMethodExpression me = null;
        final Matcher matcher = methodExpressionPattern.matcher(expression);
        Class methodExpressionClass = CriterionMethodExpression.Equal.class;
        Constructor methodExpressionConstructor = null;
        String clause = methodExpressionClass.getSimpleName();
        if (matcher.find()) {
            clause = matcher.group(1);
            methodExpressionConstructor = methodExpressions.get(clause);
            if (methodExpressionConstructor != null) {
                methodExpressionClass = methodExpressionConstructor.getDeclaringClass();
            }
        }

        String propertyName = calcPropertyName(expression, methodExpressionClass.getSimpleName());
        boolean negation = false;
        if (propertyName.endsWith(NOT)) {
            int i = propertyName.lastIndexOf(NOT);
            propertyName = propertyName.substring(0, i);
            negation = true;
        }

        if (StringUtils.isEmpty(propertyName)) {
            throw new IllegalArgumentException("No property name specified in clause: " + clause);
        }

        propertyName = NameUtils.decapitalize(propertyName);

        SourcePersistentProperty prop = entity.getPropertyByName(propertyName);
        if (prop == null) {
            Optional<String> propertyPath = entity.getPath(propertyName);
            if (propertyPath.isPresent()) {
                String[] pathArray = propertyPath.get().split("\\.");
                PersistentEntity startingEntity = entity;
                PersistentProperty startingProp;
                AssociationQuery associationQuery = null;
                AssociationQuery firstAssociationQuery = null;
                CriterionMethodExpression subExpression = null;
                for (String token : pathArray) {
                    startingProp = startingEntity.getPropertyByName(token);
                    if (startingProp == null) {
                        return buildCriterionExpression(methodExpressionConstructor, propertyName, negation);
                    } else if (startingProp instanceof Association) {
                        Association association = (Association) startingProp;
                        startingEntity = association.getAssociatedEntity();
                        if (startingEntity == null) {
                            return buildCriterionExpression(methodExpressionConstructor, propertyName, negation);
                        } else {
                            AssociationQuery newSubQuery = new AssociationQuery(association);
                            if (associationQuery != null) {
                                associationQuery.add(newSubQuery);
                            } else {
                                firstAssociationQuery = newSubQuery;
                            }
                            associationQuery = newSubQuery;
                        }
                    } else if (associationQuery != null) {
                        subExpression = buildCriterionExpression(
                                methodExpressionConstructor,
                                startingProp.getName(),
                                negation
                        );
                    }
                }
                if (firstAssociationQuery != null && subExpression != null) {
                    AssociationQuery finalQuery = firstAssociationQuery;
                    CriterionMethodExpression finalSubExpression = subExpression;
                    return new CriterionMethodExpression(finalQuery.getAssociation().getName()) {
                        @Override
                        public Query.Criterion createCriterion() {
                            Query.Criterion c = finalSubExpression.createCriterion();
                            finalQuery.add(c);
                            return finalQuery;
                        }

                        @Override
                        public int getArgumentsRequired() {
                            return finalSubExpression.getArgumentsRequired();
                        }

                        @Override
                        public void setArgumentNames(String[] argumentNames) {
                            finalSubExpression.setArgumentNames(argumentNames);
                        }
                    };
                } else {
                    return buildCriterionExpression(methodExpressionConstructor, propertyName, negation);
                }
            } else {
                return buildCriterionExpression(methodExpressionConstructor, propertyName, negation);
            }
        } else {
            return buildCriterionExpression(methodExpressionConstructor, propertyName, negation);
        }
    }

    private static CriterionMethodExpression buildCriterionExpression(Constructor methodExpressionConstructor, String propertyName, boolean negation) {
        CriterionMethodExpression me = null;
        if (methodExpressionConstructor != null) {
            try {
                me = (CriterionMethodExpression) methodExpressionConstructor.newInstance(propertyName);
            } catch (Exception e) {
                // ignore
            }
        }
        if (me == null) {
            me = new CriterionMethodExpression.Equal(propertyName);
        }
        if (negation) {
            final CriterionMethodExpression finalMe = me;
            return new CriterionMethodExpression(propertyName) {
                @Override
                public Query.Criterion createCriterion() {
                    return new Query.Negation().add(finalMe.createCriterion());
                }

                @Override
                public int getArgumentsRequired() {
                    return finalMe.getArgumentsRequired();
                }

                @Override
                public void setArgumentNames(String[] argumentNames) {
                    finalMe.setArgumentNames(argumentNames);
                }
            };
        }
        return me;
    }

    private static void resetMethodExpressionPattern() {
        String expressionPattern = String.join("|", methodExpressions.keySet());
        methodExpressionPattern = Pattern.compile("\\p{Upper}[\\p{Lower}\\d]+(" + expressionPattern + ")");
    }

    private void populateOperators(String[] operators) {
        for (int i = 0; i < operators.length; i++) {
            operatorPatterns[i] = Pattern.compile("(\\w+)(" + operators[i] + ")(\\p{Upper})(\\w+)");
        }
    }

    private static String calcPropertyName(String queryParameter, String clause) {
        String propName;
        if (clause != null && !clause.equals(CriterionMethodExpression.Equal.class.getSimpleName())) {
            int i = queryParameter.indexOf(clause);
            propName = queryParameter.substring(0, i);
        } else {
            propName = queryParameter;
        }

        return propName;
    }
}