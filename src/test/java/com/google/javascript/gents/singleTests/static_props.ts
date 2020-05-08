export class SomeClass {
  static readonly staticPropWithJsDoc: number = 0;
  static readonly staticPropWithJsDocAndNonTrivialInit: number;
}
// This is broken, we should at least declare the field like
SomeClass.staticPropNoJsDoc = 0;

// Not sure why simple initiliazers get inlined, but more
// complicated ones are left behind.
SomeClass.staticPropWithJsDocAndNonTrivialInit = 1 + 1;

// Using @const inference does not help.
SomeClass.inferred = 0;
