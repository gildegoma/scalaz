package scalaz

import ST._

/** Purely functional mutable threaded references. */
case class STRef[S, A](a: A) {
  private var value: A = a

  /** Reads the value pointed at by this reference. */
  def read: ST[S, A] = ST((s: Phantom[S]) => (s, value))

  /** Modifies the value at this reference with the given function. */
  def mod[B](f: A => A): ST[S, STRef[S, A]] = ST((s: Phantom[S]) => {value = f(value); (s, this)})

  /** Associates this reference with the given value. */
  def write(a: A): ST[S, STRef[S, A]] = ST((s: Phantom[S]) => {value = a; (s, this)})

  /** Swap the value at this reference with the value at another. */
  def swap(that: STRef[S, A]): ST[S, Unit] = for {
    v1 <- this.read
    v2 <- that.read
    _ <- this write v2
    _ <- that write v1
  } yield ()
}

/** Purely functional mutable threaded arrays. */
case class STArray[S, A:Manifest](size: Int, z: A) {
  private val value: Array[A] = Array.fill(size)(z)

  /** Reads the value at the given index. */
  def read(i: Int): ST[S, A] = ST(s => (s, value(i)))

  /** Writes the given value to the array, at the given offset. */
  def write(i: Int, a: A): ST[S, STArray[S, A]] = ST(s => {value(i) = a; (s, this)})

  /** Turns a mutable array into an immutable one which is safe to return. */
  def freeze: ST[S, ImmutableArray[A]] = ST(s => (s, ImmutableArray.fromArray(value)))

  /** Fill this array from the given association list. */
  def fill[B](f: (A, B) => A, xs: Traversable[(Int, B)]): ST[S, Unit] = xs match {
    case Nil => ST(s => (s, ()))
    case ((i, v) :: ivs) => for {
      _ <- update(f, i, v)
      _ <- fill(f, ivs)
    } yield ()
  }

  /** Combine the given value with the value at the given index, using the given function. */
  def update[B](f: (A, B) => A, i: Int, v: B) = for {
    x <- read(i)
    _ <- write(i, f(x, v))
  } yield ()

}

/** 
 * Purely functional mutable state threads.
 * Based on JL and SPJ's paper "Lazy Functional State Threads"
 */
case class ST[S, A](f: Phantom[S] => (Phantom[S], A)) {
  def apply(s: Phantom[S]) = f(s)
  def flatMap[B](g: A => ST[S, B]): ST[S, B] =
    ST(s => f(s) match { case (ns, a) => g(a)(ns) })
  def map[B](g: A => B): ST[S, B] =
    ST(s => f(s) match { case (ns, a) => (ns, g(a)) })
}

object ST {
  import Scalaz._
  import Forall._

  private[scalaz] sealed trait Phantom[S]
  private[scalaz] case class World[A]() extends Phantom[A]

  /** Run a state thread */
  def runST[A](f: Forall[({type λ[S] = ST[S, A]})#λ]): A =
    f.apply.f(World())._2

  /** Allocates a fresh mutable reference. */
  def newVar[S, A](a: A): ST[S, STRef[S, A]] =
    ST((s: Phantom[S]) => (s, STRef[S, A](a)))

  /** Allocates a fresh mutable array. */
  def newArr[S, A:Manifest](size: Int, z: A): ST[S, STArray[S, A]] =
    ST(s => (s, STArray[S, A](size, z)))

  /** Allows the result of a state transformer computation to be used lazily inside the computation. */
  def fixST[S, A](k: (=> A) => ST[S, A]): ST[S, A] = ST(s => {
    lazy val ans: (Phantom[S], A) = k(r)(s)
    lazy val (_, r) = ans
    ans
  })

  /** A monoid for sequencing ST effects. */
  implicit def stMonoid[S]: Monoid[ST[S, Unit]] = new Monoid[ST[S, Unit]] {
    val zero = ST((s: Phantom[S]) => (s, ()))
    def append(x: ST[S, Unit], y: => ST[S, Unit]) = x >>=| y
  }

  /** Accumulates an integer-associated list into an immutable array. */
  def accumArray[F[_]:Foldable, A: Manifest, B](size: Int, f: (A, B) => A, z: A, ivs: F[(Int, B)]): ImmutableArray[A] = { 
    type STA[S] = ST[S, ImmutableArray[A]]
    runST(cps[STA]((k: DNE[STA]) => k(for {
      a <- newArr(size, z)
      _ <- ivs.foldMap(x => a.update(f, x._1, x._2))
      frozen <- a.freeze
    } yield frozen)))
  }

  implicit def stMonad[S]: Monad[({ type λ[A] = ST[S, A] })#λ] = new Monad[({ type λ[A] = ST[S, A] })#λ] {
    def pure[A](a: => A) = ST(s => (s, a))
    def bind[A, B](m: ST[S, A], f: A => ST[S, B]): ST[S, B] = m flatMap f
  }

  type RealWorld = World[Nothing]

  type IO[A] = ST[RealWorld, A]
}

