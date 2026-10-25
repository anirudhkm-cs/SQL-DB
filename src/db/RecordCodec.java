package db;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


public final class RecordCodec {

    private RecordCodec() {}

    static final byte TYPE_INTEGER = 1;
    static final byte TYPE_TEXT = 2;

    public static int encodedSize(Row row) {
        int size = 1;
        for(Object value: row.values()){
            if(value instanceof Integer){
                size += 1 + 4;
            }else if(value instanceof String s){
                size += 1 + 2 + textLength(s);
            }else {
                throw new IllegalArgumentException("unsupported value type: " + value);
            }
        }
        return size;
    }

    public static byte[] encode(Row row) {
        ByteBuffer buf = ByteBuffer.allocate(encodedSize(row));
        List<Object> values = row.values();
        if(values.size() > 0xFF){
            throw new IllegalArgumentException("too many values for one record: " + values.size());
        }
        buf.put((byte) values.size());
        for(Object value: values){
            if(value instanceof Integer i){
                buf.put(TYPE_INTEGER);
                buf.putInt(i);
            }else if (value instanceof String s) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0xFFFF) {
                    throw new IllegalArgumentException("TEXT too long to encode: " + bytes.length + " bytes");
                }
                buf.put(TYPE_TEXT);
                buf.putShort((short) bytes.length); 
                buf.put(bytes);
            }else {
                throw new IllegalArgumentException("unsupported value type: " + value);
            }
        }
        return buf.array();
    }

    public static Row decode(Schema schema, ByteBuffer page, int offset) {
        List<Object> values = new ArrayList<>(schema.columnCount());
        int count = page.get(offset) & 0xFF;
        if(count>schema.columnCount()){
            throw new IllegalStateException("record at offset " + offset + " has " + count + " values but schema has " + schema.columnCount() + " columns");
        }
        int pos = offset + 1;
        for(int i=0; i<count; i++){
            Column column = schema.column(i);
            byte type = page.get(pos++);
            switch (column.type()) {
                case INTEGER-> {
                    if (type != TYPE_INTEGER) {
                        throw corrupt(offset, i, column, type);
                    }
                    values.add(page.getInt(pos));
                    pos += 4;
                }
                case TEXT -> {
                    if (type != TYPE_TEXT) {
                        throw corrupt(offset, i, column, type);
                    }
                    int length = page.getShort(pos) & 0xFFFF;
                    pos += 2;
                    byte[] bytes = new byte[length];
                    page.get(pos, bytes);            
                    pos += length;
                    values.add(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        }
        for(int i=count; i<schema.columnCount(); i++){
            values.add(defaultValue(schema.column(i)));
        }
        return new Row(values);
    }

    private static IllegalStateException corrupt(int offset, int i, Column column, byte type) {
        return new IllegalStateException("record at offset " + offset + ": column " + i
                + " (" + column.name() + ") is " + column.type() + " but stored type byte is " + type);
    }
    
    public static int maxEncodedSize(Schema schema) {
        int size = 1;
            for (Column column : schema.columns()) {
            switch (column.type()) {
                case INTEGER:
                    size += 1 + 4;                      
                    break;
                case TEXT:
                    size += 1 + 2 + column.size();      
                    break;
            }
        }
        return size;
    }

    static Object defaultValue(Column column) {
        return switch (column.type()) {
            case INTEGER -> 0;
            case TEXT -> "";
        };
    }

    static int textLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
