organization := "com.fairfax"

name := "akka-snapshotstore-dynamodb"

version := "0.3.4"

scalaVersion := "2.11.1"

crossScalaVersions := Seq("2.11.1", "2.10.4")

parallelExecution in Test := false

//resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies ++= Seq(
  "com.typesafe.akka"      %% "akka-persistence-experimental"     % "2.3.4"   % "compile",
  "com.sclasen"            %% "spray-dynamodb"                    % "0.3.2"   % "compile",
  "com.typesafe.akka"      %% "akka-testkit"                      % "2.3.4"   % "test",
  "org.scalatest"          %% "scalatest"                         % "2.1.7"   % "test",
  "com.github.krasserm"    %% "akka-persistence-testkit"          % "0.3.2"   % "test",
  "commons-io"             %  "commons-io"                        % "2.4"     % "test"
)

val root = Project("akka-snapshotstore-dynamodb", file(".")).settings(Defaults.itSettings:_*)