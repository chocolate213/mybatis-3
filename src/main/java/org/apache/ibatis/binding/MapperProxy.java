/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -4724728412955527868L;
  private static final int ALLOWED_MODES = MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
      | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC;
  private static final Constructor<Lookup> lookupConstructor;
  private static final Method privateLookupInMethod;
  private final SqlSession sqlSession;
  private final Class<T> mapperInterface;
  private final Map<Method, MapperMethodInvoker> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethodInvoker> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  static {

    // JDK 1.9 之后在 MethodHandles 方法中提供了 privateLookupIn 方法，这里判断包含该方法则为 jdk 1.9 以上
    Method privateLookupIn;
    try {
      privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
    } catch (NoSuchMethodException e) {
      privateLookupIn = null;
    }
    privateLookupInMethod = privateLookupIn;

    Constructor<Lookup> lookup = null;
    if (privateLookupInMethod == null) {
      // JDK 1.8
      try {
        // 尝试找 MethodHandles.LookUp 的私有构造方法 private Lookup(Class<?> lookupClass, int allowedModes)
        lookup = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        lookup.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException(
            "There is neither 'privateLookupIn(Class, Lookup)' nor 'Lookup(Class, int)' method in java.lang.invoke.MethodHandles.",
            e);
      } catch (Exception e) {
        lookup = null;
      }
    }
    lookupConstructor = lookup;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else {

        // 从缓存中获取 MapperMethodInvoker ，调用其 invoke 方法，如果接口中的方法不是
        // default 方法，该方法最终调用 MapperMethod 的 execute 方法
        return cachedInvoker(proxy, method, args).invoke(proxy, method, args, sqlSession);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  private MapperMethodInvoker cachedInvoker(Object proxy, Method method, Object[] args) throws Throwable {
    try {

      // 每个调用方法调用结果都将会被缓存（缓存的是 MethodInvoker），methodCache 是一个 ConcurrentHashMap
      return methodCache.computeIfAbsent(method, m -> {

        // 判断目标方法是否是一个 default 方法（Java 8 新增方法，接口类型中带有方法体的方法）, 这里如果是 default method 会做特殊处理：直接调用该方法
        if (m.isDefault()) {
          try {
            // JDK 9 之后，该方法才不为 null
            if (privateLookupInMethod == null) {
              return new DefaultMethodInvoker(getMethodHandleJava8(method));
            } else {
              return new DefaultMethodInvoker(getMethodHandleJava9(method));
            }
          } catch (IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
          }
        } else {

          // 直接调用 MapperMethod 的 invoke 方法
          return new PlainMethodInvoker(new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
        }
      });
    } catch (RuntimeException re) {
      Throwable cause = re.getCause();
      throw cause == null ? re : cause;
    }
  }

  private MethodHandle getMethodHandleJava9(Method method)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    final Class<?> declaringClass = method.getDeclaringClass();

    // Java 9 中则不用那么麻烦，下面的方法将其重构，以便更好理解

    // 首先找到 MethodHandles 中的 privateLookupIn 方法，该方法的签名如下：
    // public static Lookup privateLookupIn(Class<?> targetClass, Lookup lookup)

    // 这里为了兼容 jdk 1.8 及以下代码，使用反射的方式调用该方法，并返回一个 Lookup 对象
    Lookup lookup = (Lookup) privateLookupInMethod.invoke(null, declaringClass, MethodHandles.lookup());
    MethodType mt = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
    String methodName = method.getName();

    //
    return lookup.findSpecial(declaringClass, methodName, mt, declaringClass);
  }

  private MethodHandle getMethodHandleJava8(Method method)
      throws IllegalAccessException, InstantiationException, InvocationTargetException {

    // 目标方法所在类
    final Class<?> declaringClass = method.getDeclaringClass();

    // 这里没有使用 MethodHandles.publicLookup() 静态方法提供的 Lookup 类，而是通过反射获取到 Lookup 类的
    // 私有构造器， 并指定搜索方法类型模式为：private protected package public
    // 这里与 MethodHandles.publicLookup() 提供的 Lookup 类，只有 lookupClass 不同， ALLOWED_MODES 为 MethodHandles 的私有字段 ALL_MODES
    // Q: 这里为什么不直接使用 MethodHandles 提供的 lookup() 方法？
    // A: 如果目标方法是 Default 方法，在 JDK 1.8 中必须采用这种方式访问，否则会报错：
    // java.lang.reflect.UndeclaredThrowableException Cause By: java.lang.IllegalAccessException: no private access for invokespecial:
    return lookupConstructor.newInstance(declaringClass, ALLOWED_MODES).unreflectSpecial(method, declaringClass);
  }

  interface MapperMethodInvoker {
    Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable;
  }

  private static class PlainMethodInvoker implements MapperMethodInvoker {
    private final MapperMethod mapperMethod;

    public PlainMethodInvoker(MapperMethod mapperMethod) {
      super();
      this.mapperMethod = mapperMethod;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
      return mapperMethod.execute(sqlSession, args);
    }
  }

  private static class DefaultMethodInvoker implements MapperMethodInvoker {
    private final MethodHandle methodHandle;

    public DefaultMethodInvoker(MethodHandle methodHandle) {
      super();
      this.methodHandle = methodHandle;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {

      // 默认的 MethodInvoker 实现只是简单的调用目标方法： 接口中的 default 方法
      return methodHandle.bindTo(proxy).invokeWithArguments(args);
    }
  }
}
