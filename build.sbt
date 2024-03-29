organization := "com.fairfax"

name := "akka-persistence-dynamodb-snapshot-store"

version := "0.3.4"

scalaVersion := "2.11.1"

crossScalaVersions := Seq("2.11.1", "2.10.4")

parallelExecution in Test := false

resolvers += "spray repo" at "http://repo.spray.io"

resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

publishTo := Some("Fairfax snapshots" at "s3://ffx-newsnow-deploy-resources/public/maven")

libraryDependencies ++= Seq(
  "com.typesafe.akka"      %% "akka-persistence-experimental"     % "2.3.4"   % "compile",
  "com.sclasen"            %% "spray-dynamodb"                    % "0.3.2"   % "compile",
  "com.typesafe.akka"      %% "akka-testkit"                      % "2.3.4"   % "test",
  "org.scalatest"          %% "scalatest"                         % "2.1.7"   % "test",
  "com.github.krasserm"    %% "akka-persistence-testkit"          % "0.3.2"   % "test",
  "commons-io"             %  "commons-io"                        % "2.4"     % "test"
)

val commonSettings = net.virtualvoid.sbt.graph.Plugin.graphSettings

val root = Project("akka-persistence-dynamodb-snapshot-store", file("."))
  .settings(commonSettings:_*)
  .settings(Defaults.itSettings:_*)