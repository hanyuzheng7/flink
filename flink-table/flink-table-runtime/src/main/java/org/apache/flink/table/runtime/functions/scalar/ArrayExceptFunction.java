/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.functions.scalar;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Expressions;
import org.apache.flink.table.data.ArrayData;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.SpecializedFunction;
import org.apache.flink.table.types.CollectionDataType;
import org.apache.flink.table.types.DataType;
import org.apache.flink.util.FlinkRuntimeException;

import javax.annotation.Nullable;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.flink.table.api.Expressions.$;

/** Implementation of {@link BuiltInFunctionDefinitions#ARRAY_EXCEPT}. */
@Internal
public class ArrayExceptFunction extends BuiltInScalarFunction {
    private final ArrayData.ElementGetter elementGetter;
    private final SpecializedFunction.ExpressionEvaluator containsEvaluator;
    private transient MethodHandle containsHandle;

    public ArrayExceptFunction(SpecializedFunction.SpecializedContext context) {
        super(BuiltInFunctionDefinitions.ARRAY_EXCEPT, context);
        final DataType dataType =
                ((CollectionDataType) context.getCallContext().getArgumentDataTypes().get(0))
                        .getElementDataType();
        elementGetter = ArrayData.createElementGetter(dataType.getLogicalType());
        containsEvaluator =
                context.createEvaluator(
                        Expressions.call("HASHCODE", $("element1")),
                        DataTypes.INT(),
                        DataTypes.FIELD("element1", dataType.notNull()));
    }

    @Override
    public void open(FunctionContext context) throws Exception {
        containsHandle = containsEvaluator.open(context);
    }

    public @Nullable ArrayData eval(ArrayData arrayOne, ArrayData arrayTwo) {
        try {
            if (arrayOne == null) {
                return null;
            }

            List<Object> list = new ArrayList();
            Set<Integer> seen = new HashSet<>();

            boolean isNullPresentInArrayTwo = false;
            if (arrayTwo != null) {
                for (int pos = 0; pos < arrayTwo.size(); pos++) {
                    final Object element = elementGetter.getElementOrNull(arrayTwo, pos);
                    if (element == null) {
                        isNullPresentInArrayTwo = true;
                    } else {
                        int hashCode = (int) containsHandle.invoke(element);
                        if (!seen.contains(hashCode)) {
                            seen.add(hashCode);
                        }
                    }
                }
            }
            boolean isNullPresentInArrayOne = false;
            if (arrayOne != null) {
                for (int pos = 0; pos < arrayOne.size(); pos++) {
                    final Object element = elementGetter.getElementOrNull(arrayOne, pos);
                    if (element == null) {
                        isNullPresentInArrayOne = true;
                    } else {
                        int hashCode = (int) containsHandle.invoke(element);
                        if (!seen.contains(hashCode)) {
                            seen.add(hashCode);
                            list.add(element);
                        }
                    }
                }
            }
            if (!isNullPresentInArrayTwo && isNullPresentInArrayOne) {
                list.add(null);
            }
            return new GenericArrayData(list.toArray());
        } catch (Throwable t) {
            throw new FlinkRuntimeException(t);
        }
    }

    @Override
    public void close() throws Exception {
        containsEvaluator.close();
    }
}
