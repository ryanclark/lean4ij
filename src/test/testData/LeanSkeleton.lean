import Mathlib.Tactic

/-- A doc comment. -/
@[simp]
theorem foo (n : Nat) : n = n := by
  rfl

namespace Demo

private def bar : Nat := 42

structure P where
  x : Nat
  y : Nat

def baz : IO Nat := do
  let y := bar
  return y

def quux : Nat → Nat := fun n => n

end Demo
