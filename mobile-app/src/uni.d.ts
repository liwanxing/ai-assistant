/// <reference types="@dcloudio/types" />

// 全局 uni 对象的显式声明，供 IDE 类型提示兜底（编译层由 tsconfig 的 types 字段提供）
declare const uni: UniNamespace.Uni;
