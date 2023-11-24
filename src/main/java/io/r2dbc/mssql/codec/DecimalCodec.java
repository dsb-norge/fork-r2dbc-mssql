/*
 * Copyright 2018-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.mssql.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.r2dbc.mssql.message.tds.Encode;
import io.r2dbc.mssql.message.type.Length;
import io.r2dbc.mssql.message.type.SqlServerType;
import io.r2dbc.mssql.message.type.TdsDataType;
import io.r2dbc.mssql.message.type.TypeInformation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.function.Supplier;

/**
 * Codec for fixed floating-point values that are represented as {@link BigDecimal}.
 *
 * <ul>
 * <li>Server types: {@link SqlServerType#NUMERIC} and  {@link SqlServerType#DECIMAL}</li>
 * <li>Java type: {@link BigDecimal}</li>
 * <li>Downcast: none</li>
 * </ul>
 *
 * @author Mark Paluch
 */
final class DecimalCodec extends AbstractNumericCodec<BigDecimal> {

    private final static BigInteger MAX_VALUE = new BigInteger("99999999999999999999999999999999999999");

    static final DecimalCodec INSTANCE = new DecimalCodec();

    private static final int MAX_PRECISION = 38;

    private static final byte[] NULL = ByteArray.fromBuffer(alloc -> {

        ByteBuf buffer = alloc.buffer(4);

        Encode.asByte(buffer, 0x11);
        Encode.asByte(buffer, SqlServerType.DECIMAL.getMaxLength());
        Encode.asByte(buffer, 0); // scale
        Encode.asByte(buffer, 0); // length

        return buffer;
    });

    private DecimalCodec() {
        super(BigDecimal.class, BigDecimal::valueOf);
    }

    @Override
    Encoded doEncode(ByteBufAllocator allocator, RpcParameterContext context, BigDecimal value) {

        BigDecimal valueToUse;

        // Handle negative scale as a special case for Java 1.5 and later
        if (value.scale() < 0) {
            valueToUse = value.setScale(0);
        } else {
            valueToUse = value;
        }

        if (exceedsMaxPrecisionOrScale(valueToUse)) {
            throw new IllegalArgumentException("One or more values is out of range of values for the DECIMAL SQL type");
        }

        return new DecimalEncoded(TdsDataType.DECIMALN, () -> {

            ByteBuf buffer = RpcEncoding.prepareBuffer(allocator, TdsDataType.DECIMALN.getLengthStrategy(), 0x11, SqlServerType.DECIMAL.getMaxLength());

            encodeBigDecimal(buffer, valueToUse);
            return buffer;
        }, MAX_PRECISION, valueToUse.scale());
    }

    @Override
    Encoded doEncodeNull(ByteBufAllocator allocator) {
        return new DecimalEncoded(TdsDataType.DECIMALN, () -> Unpooled.wrappedBuffer(NULL), MAX_PRECISION, 0);
    }

    @Override
    BigDecimal doDecode(ByteBuf buffer, Length length, TypeInformation type, Class<? extends BigDecimal> valueType) {

        if (length.isNull()) {
            return null;
        }

        if (type.getServerType() == SqlServerType.DECIMAL || type.getServerType() == SqlServerType.NUMERIC) {
            return decodeDecimal(buffer, length.getLength(), type.getScale());
        }

        return super.doDecode(buffer, length, type, valueType);
    }

    private static void encodeBigDecimal(ByteBuf buffer, BigDecimal value) {

        boolean isNegative = (value.signum() < 0);

        BigInteger valueToUse = value.unscaledValue();

        if (isNegative) {
            valueToUse = valueToUse.negate();
        }

        byte[] unscaledBytes = valueToUse.toByteArray();

        Encode.asByte(buffer, value.scale());
        Encode.asByte(buffer, unscaledBytes.length + 1); // data length + sign
        Encode.asByte(buffer, isNegative ? 0 : 1);   // 1 = +ve, 0 = -ve

        for (int i = unscaledBytes.length - 1; i >= 0; i--) {
            Encode.asByte(buffer, unscaledBytes[i]);
        }
    }

    private static boolean exceedsMaxPrecisionOrScale(BigDecimal value) {

        // Maximum scale allowed is same as maximum precision allowed.
        if (value.scale() > MAX_PRECISION) {
            return true;
        }

        // Convert to unscaled integer value, then compare with maxRPCDecimalValue.
        // NOTE: Handle negative scale as a special case for Java 1.5 and later
        BigInteger bi = value.unscaledValue();
        if (value.signum() < 0) {
            bi = bi.negate();
        }
        return bi.compareTo(MAX_VALUE) > 0;
    }

    static class DecimalEncoded extends RpcEncoding.HintedEncoded {

        private final int length;

        private final int scale;

        DecimalEncoded(TdsDataType dataType, Supplier<ByteBuf> value, int length, int scale) {
            super(dataType, SqlServerType.DECIMAL, value);
            this.length = length;
            this.scale = scale;
        }

        @Override
        public String getFormalType() {
            return super.getFormalType() + "(" + this.length + "," + this.scale + ")";
        }

    }

}
