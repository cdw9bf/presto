/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator.scalar;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.Session;
import io.prestosql.metadata.FunctionListBuilder;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.SqlFunction;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.spi.ErrorCodeSupplier;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.DecimalParseResult;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.SqlDecimal;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.sql.analyzer.SemanticErrorCode;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.testing.Closeables.closeAllRuntimeException;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.FunctionExtractor.extractFunctions;
import static io.prestosql.metadata.FunctionRegistry.mangleOperatorName;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public abstract class AbstractTestFunctions
{
    protected final Session session;
    private final FeaturesConfig config;
    protected FunctionAssertions functionAssertions;

    protected AbstractTestFunctions()
    {
        this(TEST_SESSION);
    }

    protected AbstractTestFunctions(Session session)
    {
        this(session, new FeaturesConfig());
    }

    protected AbstractTestFunctions(FeaturesConfig config)
    {
        this(TEST_SESSION, config);
    }

    protected AbstractTestFunctions(Session session, FeaturesConfig config)
    {
        this.session = requireNonNull(session, "session is null");
        this.config = requireNonNull(config, "config is null");
    }

    @BeforeClass
    public final void initTestFunctions()
    {
        functionAssertions = new FunctionAssertions(session, config);
    }

    @AfterClass(alwaysRun = true)
    public final void destroyTestFunctions()
    {
        closeAllRuntimeException(functionAssertions);
        functionAssertions = null;
    }

    protected void assertFunction(String projection, Type expectedType, Object expected)
    {
        functionAssertions.assertFunction(projection, expectedType, expected);
    }

    protected void assertOperator(OperatorType operator, String value, Type expectedType, Object expected)
    {
        functionAssertions.assertFunction(format("\"%s\"(%s)", mangleOperatorName(operator), value), expectedType, expected);
    }

    protected void assertDecimalFunction(String statement, SqlDecimal expectedResult)
    {
        assertFunction(
                statement,
                createDecimalType(expectedResult.getPrecision(), expectedResult.getScale()),
                expectedResult);
    }

    // this is not safe as it catches all RuntimeExceptions
    @Deprecated
    protected void assertInvalidFunction(String projection)
    {
        functionAssertions.assertInvalidFunction(projection);
    }

    protected void assertInvalidFunction(String projection, StandardErrorCode errorCode, String messagePattern)
    {
        functionAssertions.assertInvalidFunction(projection, errorCode, messagePattern);
    }

    protected void assertInvalidFunction(String projection, String messagePattern)
    {
        functionAssertions.assertInvalidFunction(projection, INVALID_FUNCTION_ARGUMENT, messagePattern);
    }

    protected void assertInvalidFunction(String projection, SemanticErrorCode expectedErrorCode)
    {
        functionAssertions.assertInvalidFunction(projection, expectedErrorCode);
    }

    protected void assertInvalidFunction(String projection, SemanticErrorCode expectedErrorCode, String message)
    {
        functionAssertions.assertInvalidFunction(projection, expectedErrorCode, message);
    }

    protected void assertInvalidFunction(String projection, ErrorCodeSupplier expectedErrorCode)
    {
        functionAssertions.assertInvalidFunction(projection, expectedErrorCode);
    }

    protected void assertNumericOverflow(String projection, String message)
    {
        functionAssertions.assertNumericOverflow(projection, message);
    }

    protected void assertInvalidCast(String projection)
    {
        functionAssertions.assertInvalidCast(projection);
    }

    protected void assertInvalidCast(String projection, String message)
    {
        functionAssertions.assertInvalidCast(projection, message);
    }

    protected void assertCachedInstanceHasBoundedRetainedSize(String projection)
    {
        functionAssertions.assertCachedInstanceHasBoundedRetainedSize(projection);
    }

    protected void assertNotSupported(String projection, String message)
    {
        try {
            functionAssertions.executeProjectionWithFullEngine(projection);
            fail("expected exception");
        }
        catch (PrestoException e) {
            try {
                assertEquals(e.getErrorCode(), NOT_SUPPORTED.toErrorCode());
                assertEquals(e.getMessage(), message);
            }
            catch (Throwable failure) {
                failure.addSuppressed(e);
                throw failure;
            }
        }
    }

    protected void tryEvaluateWithAll(String projection, Type expectedType)
    {
        functionAssertions.tryEvaluateWithAll(projection, expectedType);
    }

    protected void registerScalarFunction(SqlScalarFunction sqlScalarFunction)
    {
        Metadata metadata = functionAssertions.getMetadata();
        metadata.getFunctionRegistry().addFunctions(ImmutableList.of(sqlScalarFunction));
    }

    protected void registerScalar(Class<?> clazz)
    {
        Metadata metadata = functionAssertions.getMetadata();
        List<SqlFunction> functions = new FunctionListBuilder()
                .scalars(clazz)
                .getFunctions();
        metadata.getFunctionRegistry().addFunctions(functions);
    }

    protected void registerParametricScalar(Class<?> clazz)
    {
        Metadata metadata = functionAssertions.getMetadata();
        List<SqlFunction> functions = new FunctionListBuilder()
                .scalar(clazz)
                .getFunctions();
        metadata.getFunctionRegistry().addFunctions(functions);
    }

    protected void registerFunctions(Plugin plugin)
    {
        functionAssertions.getMetadata().addFunctions(extractFunctions(plugin.getFunctions()));
    }

    protected void registerTypes(Plugin plugin)
    {
        for (Type type : plugin.getTypes()) {
            functionAssertions.getTypeRegistry().addType(type);
        }
    }

    protected static SqlDecimal decimal(String decimalString)
    {
        DecimalParseResult parseResult = Decimals.parseIncludeLeadingZerosInPrecision(decimalString);
        BigInteger unscaledValue;
        if (parseResult.getType().isShort()) {
            unscaledValue = BigInteger.valueOf((Long) parseResult.getObject());
        }
        else {
            unscaledValue = Decimals.decodeUnscaledValue((Slice) parseResult.getObject());
        }
        return new SqlDecimal(unscaledValue, parseResult.getType().getPrecision(), parseResult.getType().getScale());
    }

    protected static SqlDecimal maxPrecisionDecimal(long value)
    {
        final String maxPrecisionFormat = "%0" + (Decimals.MAX_PRECISION + (value < 0 ? 1 : 0)) + "d";
        return decimal(format(maxPrecisionFormat, value));
    }

    // this help function should only be used when the map contains null value
    // otherwise, use ImmutableMap.of()
    protected static <K, V> Map<K, V> asMap(List<K> keyList, List<V> valueList)
    {
        if (keyList.size() != valueList.size()) {
            fail("keyList should have same size with valueList");
        }
        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < keyList.size(); i++) {
            if (map.put(keyList.get(i), valueList.get(i)) != null) {
                fail("keyList should have same size with valueList");
            }
        }
        return map;
    }
}
