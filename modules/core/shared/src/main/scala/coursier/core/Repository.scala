package coursier.core

import coursier.core.compatibility.encodeURIComponent
import coursier.util.{EitherT, Monad}

trait Repository extends Serializable with Artifact.Source {
  def find[F[_]](
    module: Module,
    version: String,
    fetch: Repository.Fetch[F]
  )(implicit
    F: Monad[F]
  ): EitherT[F, String, (Artifact.Source, Project)]

  def completeOpt[F[_]: Monad](fetch: Repository.Fetch[F]): Option[Repository.Complete[F]] =
    None
}

object Repository {

  type Fetch[F[_]] = Artifact => EitherT[F, String, String]


  implicit class ArtifactExtensions(val underlying: Artifact) extends AnyVal {
    def withDefaultChecksums: Artifact =
      underlying.copy(checksumUrls = underlying.checksumUrls ++ Seq(
        "MD5" -> (underlying.url + ".md5"),
        "SHA-1" -> (underlying.url + ".sha1"),
        "SHA-256" -> (underlying.url + ".sha256")
      ))
    def withDefaultSignature: Artifact =
      underlying.copy(extra = underlying.extra ++ Seq(
        "sig" ->
          Artifact(
            underlying.url + ".asc",
            Map.empty,
            Map.empty,
            changing = underlying.changing,
            optional = true,
            authentication = underlying.authentication
          )
            .withDefaultChecksums
      ))
  }

  trait Complete[F[_]] {
    def organization(prefix: String): F[Either[Throwable, Seq[String]]]
    def moduleName(organization: Organization, prefix: String): F[Either[Throwable, Seq[String]]]
    def versions(module: Module, prefix: String): F[Either[Throwable, Seq[String]]]

    final def complete(input: Complete.Input)(implicit F: Monad[F]): F[Either[Throwable, Complete.Result]] = {

      // When completing names, we check if the org is there first.
      // When completing versions, we check if the org, then the name, are there first.
      // Goal is to never hit 404, that aren't cached.
      // So completing 'org.scala-lang:scala-library:' goes like:
      // - we complete first 'org', check that 'org' itself or 'org.' are in the results, stop if not
      // - we complete 'org.scala-lang', check that just 'org.scala-lang' is in the results
      // - we complete 'org.scala-lang:scala-library', check that 'org.scala-lang:scala-library' is in the results
      // - now that we know that 'org.scala-lang:scala-library' is a thing, we try to list its versions.
      // Each time we request something, we know that the parent ~element exists.

      def org(orgInput: Complete.Input.Org): F[Either[Throwable, Complete.Result]] =
        F.map(organization(orgInput.input)) {
          case Left(e) =>
            Left(new Complete.CompletingOrgException(orgInput.input, e))
          case Right(l) =>
            Right(Complete.Result(orgInput, l))
        }

      def name(nameInput: Complete.Input.Name): F[Either[Throwable, Complete.Result]] = {
        val Complete.Input.Name(org, input1, from, requiredSuffix) = nameInput

        F.map(moduleName(org, input1.drop(from))) {
          case Left(e) =>
            Left(new Complete.CompletingNameException(org, input1, from, e))
          case Right(l) =>
            val l0 = l.filter(_.endsWith(requiredSuffix)).map(_.stripSuffix(requiredSuffix))
            Right(Complete.Result(input, l0))
        }
      }

      def ver(versionInput: Complete.Input.Ver): F[Either[Throwable, Complete.Result]] = {
        val Complete.Input.Ver(mod, input1, from) = versionInput

        F.map(versions(mod, input1.drop(from))) {
          case Left(e) =>
            Left(new Complete.CompletingVersionException(mod, input1, from, e))
          case Right(l) =>
            Right(Complete.Result(input, l))
        }
      }

      def hasOrg(orgInput: Complete.Input.Org, partial: Boolean): F[Boolean] = {

        val check =
          F.map(org(orgInput)) { res =>
            res
              .right
              .toOption
              .exists { res =>
                res.completions.contains(orgInput.input) ||
                  (partial && res.completions.exists(_.startsWith(orgInput.input + ".")))
              }
          }

        val idx = orgInput.input.lastIndexOf('.')
        if (idx > 0) {
          val truncatedInput = Complete.Input.Org(orgInput.input.take(idx))
          F.bind(hasOrg(truncatedInput, partial = true)) {
            case false =>
              F.point(false)
            case true =>
              check
          }
        } else // idx < 0 || idx == 0 (idx == 0 shouldn't happen often though)
          check
      }

      def hasName(nameInput: Complete.Input.Name): F[Boolean] =
        F.map(name(nameInput)) { res =>
          res
            .right
            .toOption
            .exists(_.completions.contains(nameInput.input.drop(nameInput.from)))
        }

      def empty: F[Either[Throwable, Complete.Result]] = F.point(Right(Complete.Result(input, Nil)))

      input match {
        case orgInput: Complete.Input.Org =>
          val idx = orgInput.input.lastIndexOf('.')
          if (idx < 0)
            org(orgInput)
          else
            F.bind(hasOrg(Complete.Input.Org(orgInput.input.take(idx)), partial = true)) {
              case false => empty
              case true => org(orgInput)
            }
        case nameInput: Complete.Input.Name =>
          F.bind(hasOrg(nameInput.orgInput, partial = false)) {
            case false => empty
            case true => name(nameInput)
          }
        case verInput: Complete.Input.Ver =>
          F.bind(hasOrg(verInput.orgInput, partial = false)) {
            case false => empty
            case true =>
              F.bind(hasName(verInput.nameInput)) {
                case false => empty
                case true => ver(verInput)
              }
          }
      }
    }

    final def complete(input: String, scalaVersion: String, scalaBinaryVersion: String)(implicit F: Monad[F]): F[Either[Throwable, Complete.Result]] =
      Complete.parse(input, scalaVersion, scalaBinaryVersion) match {
        case Left(e) =>
          F.point(Left(e))
        case Right(input0) =>
          complete(input0)
      }
  }

  object Complete {
    sealed abstract class Input extends Product with Serializable {
      def input: String
      def from: Int
    }
    object Input {
      final case class Org(input: String) extends Input {
        def from: Int = 0
      }
      final case class Name(organization: Organization, input: String, from: Int, requiredSuffix: String) extends Input {
        def orgInput: Org =
          Org(organization.value)
      }
      final case class Ver(module: Module, input: String, from: Int) extends Input {
        def orgInput: Org =
          nameInput.orgInput
        def nameInput: Name = {
          val truncatedInput = module.repr
          Name(module.organization, truncatedInput, module.organization.value.length + 1, "")
        }
      }
    }

    def parse(input: String, scalaVersion: String, scalaBinaryVersion: String): Either[Throwable, Input] = {

      val idx = input.lastIndexOf(':')
      if (idx < 0)
        Right(Input.Org(input))
      else
        input.take(idx).split("\\:", -1) match {
          case Array(org) =>
            val org0 = Organization(org)
            Right(Input.Name(org0, input, idx + 1, ""))
          case Array(org, "") =>
            val org0 = Organization(org)
            Right(Input.Name(org0, input, idx + 1, "_" + scalaBinaryVersion))
          case Array(org, "", "") =>
            val org0 = Organization(org)
            Right(Input.Name(org0, input, idx + 1, "_" + scalaVersion))
          case Array(org, name) =>
            val org0 = Organization(org)
            val name0 = ModuleName(name)
            val mod = Module(org0, name0, Map())
            Right(Input.Ver(mod, input, idx + 1))
          case Array(org, "", name) if scalaBinaryVersion.nonEmpty =>
            val org0 = Organization(org)
            val name0 = ModuleName(name + "_" + scalaBinaryVersion)
            val mod = Module(org0, name0, Map())
            Right(Input.Ver(mod, input, idx + 1))
          case Array(org, "", "", name) if scalaBinaryVersion.nonEmpty =>
            val org0 = Organization(org)
            val name0 = ModuleName(name + "_" + scalaVersion)
            val mod = Module(org0, name0, Map())
            Right(Input.Ver(mod, input, idx + 1))
          case _ =>
            Left(new Complete.MalformedInput(input))
        }
    }

    final case class Result(input: Input, completions: Seq[String])

    final class CompletingOrgException(input: String, cause: Throwable = null)
      extends Exception(s"Completing organization '$input'", cause)
    final class CompletingNameException(organization: Organization, input: String, from: Int, cause: Throwable = null)
      extends Exception(s"Completing module name '${input.drop(from)}' for organization ${organization.value}", cause)
    final class CompletingVersionException(module: Module, input: String, from: Int, cause: Throwable = null)
      extends Exception(s"Completing version '${input.drop(from)}' for module $module", cause)
    final class MalformedInput(input: String)
      extends Exception(s"Malformed input '$input'")
  }
}
