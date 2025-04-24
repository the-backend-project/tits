package com.github.thxmasj.statemachine;

@FunctionalInterface
public interface BeanRegistry {

  <T> T getBean(Class<T> type);

}
