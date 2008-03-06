/**
 * MVEL (The MVFLEX Expression Language)
 *
 * Copyright (C) 2007 Christopher Brock, MVFLEX/Valhalla Project and the Codehaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.mvel;

import static org.mvel.DataConversion.canConvert;
import static org.mvel.DataConversion.convert;
import static org.mvel.MVEL.eval;
import org.mvel.ast.Function;
import org.mvel.integration.VariableResolverFactory;
import org.mvel.util.MethodStub;
import org.mvel.util.ParseTools;
import static org.mvel.util.ParseTools.*;
import static org.mvel.util.PropertyTools.*;
import org.mvel.util.StringAppender;

import static java.lang.Character.isJavaIdentifierPart;
import static java.lang.Character.isWhitespace;
import java.lang.reflect.*;
import static java.lang.reflect.Array.getLength;
import java.util.*;
import static java.util.Collections.synchronizedMap;

@SuppressWarnings({"unchecked"})
public class PropertyAccessor {
    private int start = 0;
    private int cursor = 0;

    private char[] property;
    private int length;

    private Object thisReference;
    private Object ctx;
    private Object curr;

    private boolean first = true;
    private boolean nullHandle = false;

    private VariableResolverFactory variableFactory;

    private static final int DONE = -1;
    private static final int NORM = 0;
    private static final int METH = 1;
    private static final int COL = 2;

    private static final Object[] EMPTYARG = new Object[0];

    private static Map<Class, Map<Integer, Member>> READ_PROPERTY_RESOLVER_CACHE;
    private static Map<Class, Map<Integer, Member>> WRITE_PROPERTY_RESOLVER_CACHE;
    private static Map<Class, Map<Integer, Object[]>> METHOD_RESOLVER_CACHE;

    static {
        configureFactory();
    }

    static void configureFactory() {
        if (MVEL.THREAD_SAFE) {
            READ_PROPERTY_RESOLVER_CACHE = synchronizedMap(new WeakHashMap<Class, Map<Integer, Member>>(10));
            WRITE_PROPERTY_RESOLVER_CACHE = synchronizedMap(new WeakHashMap<Class, Map<Integer, Member>>(10));
            METHOD_RESOLVER_CACHE = synchronizedMap(new WeakHashMap<Class, Map<Integer, Object[]>>(10));
        }
        else {
            READ_PROPERTY_RESOLVER_CACHE = (new WeakHashMap<Class, Map<Integer, Member>>(10));
            WRITE_PROPERTY_RESOLVER_CACHE = (new WeakHashMap<Class, Map<Integer, Member>>(10));
            METHOD_RESOLVER_CACHE = (new WeakHashMap<Class, Map<Integer, Object[]>>(10));
        }
    }

    public PropertyAccessor(char[] property, Object ctx) {
        this.property = property;
        this.length = property.length;
        this.ctx = ctx;
    }

    public PropertyAccessor(char[] property, Object ctx, VariableResolverFactory resolver, Object thisReference) {
        this.property = property;
        this.length = property.length;
        this.ctx = ctx;
        this.variableFactory = resolver;
        this.thisReference = thisReference;
    }

    public PropertyAccessor(char[] property, Object ctx, Object thisRef, VariableResolverFactory resolver, Object thisReference) {
        this.property = property;
        this.length = property.length;
        this.ctx = ctx;
        this.thisReference = thisRef;
        this.variableFactory = resolver;
        this.thisReference = thisReference;
    }

    public PropertyAccessor(VariableResolverFactory resolver, Object thisReference) {
        this.variableFactory = resolver;
        this.thisReference = thisReference;
    }


    public PropertyAccessor(char[] property, int offset, int end, Object ctx, VariableResolverFactory resolver) {
        this.property = property;
        this.cursor = offset;
        this.length = end;
        this.ctx = ctx;
        this.variableFactory = resolver;
    }

    public PropertyAccessor(String property, Object ctx) {
        this.length = (this.property = property.toCharArray()).length;
        this.ctx = ctx;
    }

    public static Object get(String property, Object ctx) {
        return new PropertyAccessor(property, ctx).get();
    }

    public static Object get(char[] property, Object ctx, VariableResolverFactory resolver, Object thisReference) {
        return new PropertyAccessor(property, ctx, resolver, thisReference).get();
    }

    public static Object get(char[] property, int offset, int end, Object ctx, VariableResolverFactory resolver) {
        return new PropertyAccessor(property, offset, end, ctx, resolver).get();
    }

    public static Object get(String property, Object ctx, VariableResolverFactory resolver, Object thisReference) {
        return new PropertyAccessor(property.toCharArray(), ctx, resolver, thisReference).get();
    }

    public static void set(Object ctx, String property, Object value) {
        new PropertyAccessor(property, ctx).set(value);
    }

    public static void set(Object ctx, VariableResolverFactory resolver, String property, Object value) {
        new PropertyAccessor(property.toCharArray(), ctx, resolver, null).set(value);
    }

    private Object get() {
        curr = ctx;
        try {
            while (cursor < length) {
                switch (nextToken()) {
                    case NORM:
                        curr = getBeanProperty(curr, capture());
                        break;
                    case METH:
                        curr = getMethod(curr, capture());
                        break;
                    case COL:
                        curr = getCollectionProperty(curr, capture());
                        break;
                    case DONE:
                }

                if (nullHandle) {
                    if (curr == null) {
                        return null;
                    }
                    else {
                        nullHandle = false;
                    }
                }

                first = false;
            }

            return curr;
        }
        catch (InvocationTargetException e) {
            throw new PropertyAccessException("could not access property", e);
        }
        catch (IllegalAccessException e) {
            throw new PropertyAccessException("could not access property", e);
        }
        catch (IndexOutOfBoundsException e) {
            throw new PropertyAccessException("array or collections index out of bounds (property: " + new String(property) + ")", e);
        }
        catch (PropertyAccessException e) {
            throw new PropertyAccessException("failed to access property: <<" + new String(property) + ">> in: " + (ctx != null ? ctx.getClass() : null), e);
        }
        catch (CompileException e) {
            throw e;
        }
        catch (NullPointerException e) {
            throw new PropertyAccessException("null pointer exception in property: " + new String(property), e);
        }
        catch (Exception e) {
            throw new PropertyAccessException("unknown exception in expression: " + new String(property), e);
        }
    }

    private void set(Object value) {
        curr = ctx;

        try {
            int oLength = length;

            length = findAbsoluteLast(property);

            if ((curr = get()) == null)
                throw new PropertyAccessException("cannot bind to null context: " + new String(property));

            length = oLength;

            if (nextToken() == COL) {
                int start = ++cursor;

                whiteSpaceSkip();

                if (cursor == length)
                    throw new PropertyAccessException("unterminated '['");

                if (!scanTo(']'))
                    throw new PropertyAccessException("unterminated '['");

                String ex = new String(property, start, cursor - start);

                if (curr instanceof Map) {
                    //noinspection unchecked
                    ((Map) curr).put(eval(ex, this.ctx, this.variableFactory), value);
                }
                else if (curr instanceof List) {
                    //noinspection unchecked
                    ((List) curr).set(eval(ex, this.ctx, this.variableFactory, Integer.class), value);
                }
                else if (curr.getClass().isArray()) {
                    Array.set(curr, eval(ex, this.ctx, this.variableFactory, Integer.class), convert(value, getBaseComponentType(curr.getClass())));
                }

                else {
                    throw new PropertyAccessException("cannot bind to collection property: " + new String(property) + ": not a recognized collection type: " + ctx.getClass());
                }

                return;
            }

            String tk = capture();


            Member member = checkWriteCache(curr.getClass(), tk == null ? 0 : tk.hashCode());
            if (member == null) {
                addWriteCache(curr.getClass(), tk == null ? 0 : tk.hashCode(), (member = getFieldOrWriteAccessor(curr.getClass(), tk)));
            }

            if (member instanceof Field) {
                Field fld = (Field) member;

                if (value != null && !fld.getType().isAssignableFrom(value.getClass())) {
                    if (!canConvert(fld.getType(), value.getClass())) {
                        throw new ConversionException("cannot convert type: "
                                + value.getClass() + ": to " + fld.getType());
                    }

                    fld.set(curr, convert(value, fld.getType()));
                }
                else {
                    fld.set(curr, value);
                }
            }
            else if (member != null) {
                Method meth = (Method) member;

                if (value != null && !meth.getParameterTypes()[0].isAssignableFrom(value.getClass())) {
                    if (!canConvert(meth.getParameterTypes()[0], value.getClass())) {
                        throw new ConversionException("cannot convert type: "
                                + value.getClass() + ": to " + meth.getParameterTypes()[0]);
                    }
                    meth.invoke(curr, convert(value, meth.getParameterTypes()[0]));
                }
                else {
                    meth.invoke(curr, value);
                }
            }
            else if (curr instanceof Map) {
                //noinspection unchecked
                ((Map) curr).put(eval(tk, this.ctx, this.variableFactory), value);
            }
            else {
                throw new PropertyAccessException("could not access property (" + tk + ") in: " + ctx.getClass().getName());
            }
        }
        catch (InvocationTargetException e) {
            throw new PropertyAccessException("could not access property", e);
        }
        catch (IllegalAccessException e) {
            throw new PropertyAccessException("could not access property", e);
        }

    }

    private int nextToken() {
        switch (property[start = cursor]) {
            case '[':
                return COL;
            case '.':
                // ++cursor;
                if (property[cursor = ++start] == '?') {
                    cursor = ++start;
                    nullHandle = true;
                }
        }

        while (cursor < length && isWhitespace(property[cursor])) cursor++;
        start = cursor;

        //noinspection StatementWithEmptyBody
        while (++cursor < length && isJavaIdentifierPart(property[cursor])) ;

        if (cursor < length) {
            while (isWhitespace(property[cursor])) cursor++;
            switch (property[cursor]) {
                case '[':
                    return COL;
                case '(':
                    return METH;
                default:
                    return 0;
            }
        }
        return 0;
    }

    private String capture() {
        return new String(property, start, trimLeft(cursor) - start);
    }

    protected int trimLeft(int pos) {
        while (pos > 0 && isWhitespace(property[pos - 1])) pos--;
        return pos;
    }

    public static void clearPropertyResolverCache() {
        READ_PROPERTY_RESOLVER_CACHE.clear();
        WRITE_PROPERTY_RESOLVER_CACHE.clear();
        METHOD_RESOLVER_CACHE.clear();
    }

    public static void reportCacheSizes() {
        System.out.println("read property cache: " + READ_PROPERTY_RESOLVER_CACHE.size());
        for (Class cls : READ_PROPERTY_RESOLVER_CACHE.keySet()) {
            System.out.println(" [" + cls.getName() + "]: " + READ_PROPERTY_RESOLVER_CACHE.get(cls).size() + " entries.");
        }
        System.out.println("write property cache: " + WRITE_PROPERTY_RESOLVER_CACHE.size());
        for (Class cls : WRITE_PROPERTY_RESOLVER_CACHE.keySet()) {
            System.out.println(" [" + cls.getName() + "]: " + WRITE_PROPERTY_RESOLVER_CACHE.get(cls).size() + " entries.");
        }
        System.out.println("method cache: " + METHOD_RESOLVER_CACHE.size());
        for (Class cls : METHOD_RESOLVER_CACHE.keySet()) {
            System.out.println(" [" + cls.getName() + "]: " + METHOD_RESOLVER_CACHE.get(cls).size() + " entries.");
        }
    }

    private static void addReadCache(Class cls, Integer property, Member member) {
        Map<Integer, Member> nestedMap = READ_PROPERTY_RESOLVER_CACHE.get(cls);

        if (nestedMap == null) {
            READ_PROPERTY_RESOLVER_CACHE.put(cls, nestedMap = new WeakHashMap<Integer, Member>());
        }
        nestedMap.put(property, member);
    }

    private static Member checkReadCache(Class cls, Integer property) {
        Map<Integer, Member> map = READ_PROPERTY_RESOLVER_CACHE.get(cls);
        if (map != null) {
            return map.get(property);
        }
        return null;
    }

    private static void addWriteCache(Class cls, Integer property, Member member) {
        Map<Integer, Member> map = WRITE_PROPERTY_RESOLVER_CACHE.get(cls);
        if (map == null) {
            WRITE_PROPERTY_RESOLVER_CACHE.put(cls, map = new WeakHashMap<Integer, Member>());
        }
        map.put(property, member);
    }

    private static Member checkWriteCache(Class cls, Integer property) {
        Map<Integer, Member> map = WRITE_PROPERTY_RESOLVER_CACHE.get(cls);
        if (map != null) {
            return map.get(property);
        }
        return null;
    }


    private static void addMethodCache(Class cls, Integer property, Method member) {
        Map<Integer, Object[]> map = METHOD_RESOLVER_CACHE.get(cls);
        if (map == null) {
            METHOD_RESOLVER_CACHE.put(cls, map = new WeakHashMap<Integer, Object[]>());
        }
        map.put(property, new Object[]{member, member.getParameterTypes()});
    }

    private static Object[] checkMethodCache(Class cls, Integer property) {
        Map<Integer, Object[]> map = METHOD_RESOLVER_CACHE.get(cls);
        if (map != null) {
            return map.get(property);
        }
        return null;
    }



    private Object getBeanProperty(Object ctx, String property)
            throws IllegalAccessException, InvocationTargetException {

        if (first) {
            if ("this".equals(property)) {
                return this.thisReference;
            }
            else if (variableFactory != null && variableFactory.isResolveable(property)) {
                return variableFactory.getVariableResolver(property).getValue();
            }
        }

        Class cls;
        Member member = checkReadCache(cls = (ctx instanceof Class ? ((Class) ctx) : ctx.getClass()), property.hashCode());

        if (member == null) {
            addReadCache(cls, property.hashCode(), member = getFieldOrAccessor(cls, property));
        }

        if (member instanceof Field) {
            return ((Field) member).get(ctx);
        }
        else if (member != null) {
            try {
                return ((Method) member).invoke(ctx, EMPTYARG);
            }
            catch (IllegalAccessException e) {
                synchronized (member) {
                    try {
                        ((Method) member).setAccessible(true);
                        return ((Method) member).invoke(ctx, EMPTYARG);
                    }
                    finally {
                        ((Method) member).setAccessible(false);
                    }
                }
            }

        }
        else if (ctx instanceof Map && ((Map) ctx).containsKey(property)) {
            return ((Map) ctx).get(property);
        }
        else if ("length".equals(property) && ctx.getClass().isArray()) {
            return Array.getLength(ctx);
        }
        else if (ctx instanceof Class) {
            Class c = (Class) ctx;
            for (Method m : c.getMethods()) {
                if (property.equals(m.getName())) {
                    return m;
                }
            }
        }

        throw new PropertyAccessException("could not access property (" + property + ")");
    }

    private void whiteSpaceSkip() {
        if (cursor < length)
            //noinspection StatementWithEmptyBody
            while (isWhitespace(property[cursor]) && ++cursor < length) ;
    }

    private boolean scanTo(char c) {
        for (; cursor < length; cursor++) {
            if (property[cursor] == c) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle accessing a property embedded in a collections, map, or array
     *
     * @param ctx  -
     * @param prop -
     * @return -
     * @throws Exception -
     */
    private Object getCollectionProperty(Object ctx, String prop) throws Exception {
        if (prop.length() != 0) {
            ctx = getBeanProperty(ctx, prop);
        }

        int start = ++cursor;

        whiteSpaceSkip();

        if (cursor == length)
            throw new PropertyAccessException("unterminated '['");

        Object item;

        if (!scanTo(']'))
            throw new PropertyAccessException("unterminated '['");

        // String ex = new String(property, start, cursor++ - start);
        item = eval(new String(property, start, cursor++ - start), ctx, variableFactory);

        if (ctx instanceof Map) {
            return ((Map) ctx).get(item);
        }
        else if (ctx instanceof List) {
            return ((List) ctx).get((Integer) item);
        }
        else if (ctx instanceof Collection) {
            int count = (Integer) item;
            if (count > ((Collection) ctx).size())
                throw new PropertyAccessException("index [" + count + "] out of bounds on collections");

            Iterator iter = ((Collection) ctx).iterator();
            for (int i = 0; i < count; i++) iter.next();
            return iter.next();
        }
        else if (ctx instanceof Object[]) {
            return ((Object[]) ctx)[(Integer) item];
        }
        else if (ctx instanceof CharSequence) {
            return ((CharSequence) ctx).charAt((Integer) item);
        }
        else {
            throw new PropertyAccessException("illegal use of []: unknown type: " + (ctx == null ? null : ctx.getClass().getName()));
        }
    }

    /**
     * Find an appropriate method, execute it, and return it's response.
     *
     * @param ctx  -
     * @param name -
     * @return -
     * @throws Exception -
     */
    @SuppressWarnings({"unchecked"})
    private Object getMethod(Object ctx, String name) throws Exception {
        int st = cursor;
        String tk = ((cursor = balancedCapture(property, cursor, '(')) - st) > 1 ?
                new String(property, st + 1, cursor - st - 1) : "";

        cursor++;

        Object[] args;
        if (tk.length() == 0) {
            args = ParseTools.EMPTY_OBJ_ARR;
        }
        else {
            String[] subtokens = parseParameterList(tk.toCharArray(), 0, -1);
            args = new Object[subtokens.length];
            for (int i = 0; i < subtokens.length; i++) {
                args[i] = eval(subtokens[i], thisReference, variableFactory);
            }
        }

        if (first && variableFactory != null && variableFactory.isResolveable(name)) {
            Object ptr = variableFactory.getVariableResolver(name).getValue();
            if (ptr instanceof Method) {
                ctx = ((Method) ptr).getDeclaringClass();
                name = ((Method) ptr).getName();
            }
            else if (ptr instanceof MethodStub) {
                ctx = ((MethodStub) ptr).getClassReference();
                name = ((MethodStub) ptr).getMethodName();
            }
            else if (ptr instanceof Function) {
                return ((Function) ptr).call(ctx, thisReference, variableFactory, args);
            }
            else {
                throw new OptimizationFailure("attempt to optimize a method call for a reference that does not point to a method: "
                        + name + " (reference is type: " + (ctx != null ? ctx.getClass().getName() : null) + ")");
            }

            first = false;
        }

        /**
         * If the target object is an instance of java.lang.Class itself then do not
         * adjust the Class scope target.
         */
        Class cls = ctx instanceof Class ? (Class) ctx : ctx.getClass();

        /**
         * Check to see if we have already cached this method;
         */
        Object[] cache = checkMethodCache(cls, createSignature(name, tk));

        Method m;
        Class[] parameterTypes;

        if (cache != null) {
            m = (Method) cache[0];
            parameterTypes = (Class[]) cache[1];
        }
        else {
            m = null;
            parameterTypes = null;
        }

        /**
         * If we have not cached the method then we need to go ahead and try to resolve it.
         */
        if (m == null) {
            /**
             * Try to find an instance method from the class target.
             */

            if ((m = getBestCandidate(args, name, cls.getMethods())) != null) {
                addMethodCache(cls, createSignature(name, tk), m);
                parameterTypes = m.getParameterTypes();
            }

            if (m == null) {
                /**
                 * If we didn't find anything, maybe we're looking for the actual java.lang.Class methods.
                 */
                if ((m = getBestCandidate(args, name, cls.getClass().getDeclaredMethods())) != null) {
                    addMethodCache(cls, createSignature(name, tk), m);
                    parameterTypes = m.getParameterTypes();
                }
            }
        }

        if (m == null) {
            StringAppender errorBuild = new StringAppender();
            for (int i = 0; i < args.length; i++) {
                errorBuild.append(args[i] != null ? args[i].getClass().getName() : null);
                if (i < args.length - 1) errorBuild.append(", ");
            }

            if ("size".equals(name) && args.length == 0 && cls.isArray()) {
                return getLength(ctx);
            }

            throw new PropertyAccessException("unable to resolve method: " + cls.getName() + "." + name + "(" + errorBuild.toString() + ") [arglength=" + args.length + "]");
        }
        else {
            for (int i = 0; i < args.length; i++) {
                args[i] = convert(args[i], parameterTypes[i]);
            }

            /**
             * Invoke the target method and return the response.
             */
            return m.invoke(ctx, args);
        }
    }

    private static int createSignature(String name, String args) {
        return name.hashCode() + args.hashCode();
    }

}
