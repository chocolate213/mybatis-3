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
package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

  public static final String GENERIC_NAME_PREFIX = "param";

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   */
  private final SortedMap<Integer, String> names;

  private boolean hasParamAnnotation;

  public ParamNameResolver(Configuration config, Method method) {
    final Class<?>[] paramTypes = method.getParameterTypes();

    // 获取方法参数上的 annotation
    // Q: 为什么返回的是一个 二维数组？
    // A: 因为每个参数上可能会有多个 annotation
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<>();

    // 这里直接去 annotation 数组的大小，即使一个参数并没有声明注解，这个数组也会包含所有参数列表的大小
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {

      // 判断参数是否是 RowBounds.class 或者 ResultHandler.class 的子类，如果是则跳过
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters
        continue;
      }


      String name = null;
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        if (annotation instanceof Param) {
          hasParamAnnotation = true;

          // 找到这个参数的名字，这里指定的是SQL中占位符的名字，指定这个参数要替换掉SQL中指定的占位符：
          // e.g: select * from user where id = ${user_id}
          // User selectById(@Param("user_id") Integer id)
          name = ((Param) annotation).value();
          break;
        }
      }
      if (name == null) {
        // @Param was not specified.

        // 这是 mybatis 中的一项配置，是否使用真实的参数名
        if (config.isUseActualParamName()) {

          // 要想获取到参数名，必须使用 jdk 1.8 及以上版本编译，且在编译时指定 -parameters 参数
          name = getActualParamName(method, paramIndex);
        }
        if (name == null) {
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71

          // map.size() 在解析成功之后将会递增
          name = String.valueOf(map.size());
        }
      }
      map.put(paramIndex, name);
    }

    // names map 为 参数角标: 参数名称，这个名称可能是参数顺序，或者是 @Param 中配置的 value
    names = Collections.unmodifiableSortedMap(map);
  }

  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   */
  public Object getNamedParams(Object[] args) {
    final int paramCount = names.size();
    if (args == null || paramCount == 0) {
      return null;
    } else if (!hasParamAnnotation && paramCount == 1) {

      // 如果只有一个参数，那直接取出这个参数值
      return args[names.firstKey()];
    } else {

      final Map<String, Object> param = new ParamMap<>();
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {

        // key 是 names 中的 value，value 为 @Param(value) value的值，或者是 参数名
        // value 是 接口方法上的值
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        final String genericParamName = GENERIC_NAME_PREFIX + (i + 1);
        // ensure not to overwrite parameter named with @Param

        if (!names.containsValue(genericParamName)) {

          // 这里又生成了一个 param1 param2 对应 方法声明顺序的名字，所以在 xml 文件或者sql中，既可以使用参数名、@Param 中配置的值，也可以使用 param1 param2 来指定占位符：
          // SELECT * FROM USER where user_id = ${param1}
          // SELECT * FROM USER where user_id = ${username}
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }

      // 返回的是 参数名 -> 参数传入值的 map
      return param;
    }
  }
}
