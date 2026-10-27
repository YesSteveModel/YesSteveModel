package com.elfmcys.ysm.format.parser.pojo.manifest.models;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@JsonAdapter(ModelFilesWarper.AdapterFactory.class)
public class ModelFilesWarper<T> {
    public List<T> list = new ArrayList<>();

    public ModelFilesWarper() {}

    public ModelFilesWarper(List<T> list) {
        this.list = list != null ? list : new ArrayList<>();
    }

    public static final class AdapterFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!ModelFilesWarper.class.isAssignableFrom(type.getRawType())) {
                return null;
            }
            if (!(type.getType() instanceof ParameterizedType)) {
                throw new JsonParseException("ModelFilesWarper requires a parameterized element type.");
            }

            Type elementType = ((ParameterizedType)type.getType()).getActualTypeArguments()[0];
            TypeAdapter<?> elementAdapter = gson.getAdapter(TypeToken.get(elementType));
            TypeAdapter<?> listAdapter = gson.getAdapter(TypeToken.getParameterized(List.class, elementType));
            return (TypeAdapter<T>)new Adapter<>(elementAdapter, listAdapter);
        }
    }

    private static final class Adapter<E> extends TypeAdapter<ModelFilesWarper<E>> {
        private final TypeAdapter<E> elementAdapter;
        private final TypeAdapter<List<E>> listAdapter;

        @SuppressWarnings("unchecked")
        private Adapter(TypeAdapter<?> elementAdapter, TypeAdapter<?> listAdapter) {
            this.elementAdapter = (TypeAdapter<E>)elementAdapter;
            this.listAdapter = (TypeAdapter<List<E>>)listAdapter;
        }

        @Override
        public ModelFilesWarper<E> read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            ModelFilesWarper<E> wrapper = new ModelFilesWarper<>();

            if (token == JsonToken.NULL) {
                in.nextNull();
                return wrapper;
            }
            if (token == JsonToken.BEGIN_ARRAY) {
                List<E> list = listAdapter.read(in);
                wrapper.list = list != null ? list : new ArrayList<>();
                return wrapper;
            }
            if (token != JsonToken.BEGIN_OBJECT) {
                throw new JsonParseException("Expected object, array, or null for ModelFilesWarper but was " + token);
            }

            in.beginObject();
            while (in.hasNext()) {
                String id = in.nextName();
                E files = elementAdapter.read(in);
                appendObjectEntry(wrapper, id, files);
            }
            in.endObject();
            return wrapper;
        }

        @Override
        public void write(JsonWriter out, ModelFilesWarper<E> value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            listAdapter.write(out, value.list);
        }

        private static <E> void appendObjectEntry(ModelFilesWarper<E> wrapper, String id, E files) {
            if (files instanceof ReplacedModelFiles matchable) {
                if (matchable.match == null) {
                    matchable.match = new ArrayList<>();
                }
                matchable.match.add(id);
            }
            wrapper.list.add(files);
        }
    }
}
