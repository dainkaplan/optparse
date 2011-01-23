
package org.fud.optparse


import collection.mutable.ListBuffer

class OptionParserException(m: String) extends RuntimeException(m)
// This exception should be thrown by convertion functions upon any error.
class InvalidArgumentException(m: String) extends OptionParserException(m) { def this() = this("") }

/**
 * Option parser class based on Ruby class of same name.
 * Support command line parsing using POSIX style short and long arguments.
*/

class OptionParser {
  
  var auto_help = true   // Set to false if you don't want -h, --help auto added
                         // Or simply add your own switch for either -h, or --help to override.
                         // The default help action will print help and exit(0)
  var verbose_errors = true  // Use verbose error messages
  
  protected val argv = new ListBuffer[String]
  protected var switches = new ListBuffer[Switch]
  
  protected var _curr_arg_display = ""  // Used for error reporting
  def curr_arg_display = _curr_arg_display
  
  def help = (if (banner.isEmpty) "" else banner + "\n") + switches.mkString("\n")
  override def toString = help
  
  // The banner is displayed first by the help/toString methods
  var banner = ""
  
  // --------------------------------------------------------------------------------------------
  def separator(text: String) = addSwitch(new Separator(text))
  
  // --------------------------------------------------------------------------------------------
  // Define a switch that takes no arguments
  def noArg(short: String, long: String, info: String*)(func: () => Unit): Unit =
    addSwitch(new NoArgSwitch(getNames(short, long), info, func))

  // --------------------------------------------------------------------------------------------
  // Define a switch that takes a required argument
  def reqArg[T](short: String, long: String, info: String*)(func: T => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ArgSwitch(getNames(short, long), info, converter(m), func))
  
  // Define a switch that takes a required argument where the valid values are given by a Seq[]
  def reqArg[T](short: String, long: String, vals: Seq[T], info: String*)(func: T => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))

  // Define a switch that takes a required argument where the valid values are given by a Map
  def reqArg[T](short: String, long: String, vals: Map[String, T], info: String*)(func: T => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))

  // --------------------------------------------------------------------------------------------
  // Define a switch that takes an optional argument
  def optArg[T](short: String, long: String, info: String*)(func: Option[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new OptArgSwitch(getNames(short, long), info, converter(m), func))

  // Define a switch that takes an optional argument where the valid values are given by a Seq[]
  def optArg[T](short: String, long: String, vals: Seq[T], info: String*)(func: Option[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new OptArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))
  
  // Define a switch that takes an optional argument where the valid values are given by a Map
  def optArg[T](short: String, long: String, vals: Map[String, T], info: String*)(func: Option[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new OptArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))
  
    // Define a switch that takes a comma separated list of arguments.
  def listArg[T](short: String, long: String, info: String*)(func: List[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ListArgSwitch(getNames(short, long), info, converter(m), func))

  
  // --------------------------------------------------------------------------------------------
  // Parse the given command line.  Each token from the command line should be
  // in a separate entry in the given sequence such as the array of strings passed
  // to def main(args: Array[String]) {}
  // The options are processed using the code that has been previously specified when
  // setting up the parser.  A List[String] of all non-option arguments is returned.
  def parse(args: Seq[String]): List[String] = {
    // Pluck a switch argument from argv. If greedy we always take it.
    // If not we take it if it does not begin with a dash.
    // (Special case: A single '-' represents the stdin arg and is plucked)
    def pluckArg(greedy: Boolean): Option[String] = {
      if (greedy && argv.isEmpty) throw new ArgumentMissing
      
      if (argv.nonEmpty && (greedy || !(argv(0).startsWith("-") && argv(0).length > 1))) {
        val a = argv.remove(0)
        _curr_arg_display += (" " + a)  // Update for error reporting
        Some(a)
      }
      else
        None
    }
    
    if (auto_help && !switches.exists(s => s.short == "h" || s.long == "--help"))
      this.noArg("-h", "--help", "Show this message") { () => println(this); exit(0) }
    val non_switch_args = new ListBuffer[String]
    argv.clear // Clear any remnants
    argv ++= args
    
    var terminate = false
    while (!terminate) {
      nextToken match {
        case Terminate()    => non_switch_args ++= argv; terminate = true
        case NonSwitch(arg) => non_switch_args += arg
        case SwitchToken(switch, longForm, joinedArg) =>
          var arg = (joinedArg, switch.takesArg, longForm) match {
            case (Some(a), true,  _)     => Some(a)
            case (Some(a), false, true)  => throw new NeedlessArgument
            case (Some(a), false, false) => ("-" + a) +=: argv; None  // short switches can be joined so put the arg back with a - prefix                
            case (None,    false, _)     => None
            case (None,    true,  _)     => pluckArg(switch.requiresArg)
          }
          switch.process(arg)
      }
    }

    non_switch_args.toList
  }
  
  // Register a converter function for the given 
  def addConverter[T](f: String => T)(implicit m: ClassManifest[T]): Unit = {
    val wrapped = { s: String =>
      try { f(s) } 
      catch { 
        case e: InvalidArgumentException if e.getMessage.isEmpty || !verbose_errors => throw new InvalidArgument
        case e: InvalidArgumentException => throw new InvalidArgument("   (%s)".format(e.getMessage))
      } 
    }
    _converters = (m -> wrapped) :: _converters
  }
    
  protected class ArgumentMissing extends OptionParserException("argument missing: " + curr_arg_display)
  protected class InvalidArgument(m: String) extends OptionParserException("invalid argument: " + curr_arg_display + m) {
    def this() = this("")
  }
  protected class AmbiguousArgument(m: String) extends OptionParserException("ambiguous argument: " + curr_arg_display + m)
  protected class NeedlessArgument extends OptionParserException("needless argument: " + curr_arg_display)
  protected class InvalidOption extends OptionParserException("invalid option: " + curr_arg_display)
  protected class AmbiguousOption(m: String) extends OptionParserException("ambiguous option: " + curr_arg_display + m) {
    def this() = this("")    
  }
  
  protected abstract class Token
  
  protected case class Terminate() extends Token

  protected case class NonSwitch(arg: String) extends Token

  // Container for internal and display names of a switch.
  protected case class Names(short: String, long: String, display: String) {
    override val toString = display
  }
  
  // A switch was parsed on the command line.
  // We indicate where the long form (eg. --type) was used and
  // if there was a joined token:
  //   -tbinary  or  --type=binary 
  protected case class SwitchToken(switch: Switch, longForm: Boolean, joinedArg: Option[String]) extends Token
  
  
  // The short and long names are stored without leading '-' or '--'
  protected abstract class Switch(names: Names, val info: Seq[String] = List()) extends Token {
    val takesArg: Boolean = false
    val requiresArg: Boolean = false
    def short = names.short
    def long  = names.long
    
    // Called when this switch is detected on the command line.  Should handle the
    // invocation of the user's code to process this switch.
    def process(arg: Option[String]): Unit = {}
    
    override lazy val toString = {
      val sw   = "    " + names
      val sep  = "\n" + " " * 37
      val sep1 = if (sw.length < 37) " " * (37 - sw.length) else sep
      sw + info.mkString(sep1, sep, "")
    }
  }
  
  protected class Separator(text: String) extends Switch(Names("", "", ""), Seq()) {
    override lazy val toString = text
  }
  
  protected class NoArgSwitch(n: Names, d: Seq[String], func: () => Unit) extends Switch(n, d) {
    override def process(arg: Option[String]): Unit = func()
  }
  
  protected class ArgSwitch[T](n: Names, d: Seq[String], convert: String => T, func: T => Unit) extends Switch(n, d) {
    override val takesArg    = true
    override val requiresArg = true
    
    override def process(arg: Option[String]): Unit = arg match {
      case None => throw new RuntimeException("Internal error - no arg for ArgSwitch")
      case Some(a) => func(convert(a))
    }
  }

  protected class OptArgSwitch[T](n: Names, d: Seq[String], convert: String => T, func: Option[T] => Unit) extends Switch(n, d) {
    override val takesArg = true
    override def process(arg: Option[String]): Unit = func(arg.map(convert))
  }
  
  protected class ListArgSwitch[T](n: Names, d: Seq[String], convert: String => T, func: List[T] => Unit) extends Switch(n, d) {
    override val takesArg    = true
    override val requiresArg = true
    override def process(arg: Option[String]): Unit = arg match {
      case None => throw new RuntimeException("Internal error - no arg for ListArgSwitch")
      case Some(argList) => func(argList.split(",").toList.map(convert))
    }
  }
    
  // Clas to hold a list of valid values. Maps the string representation to it's actual value.
  // Support partial matching on the strings
  protected class ValueList[T](vals: List[(String, T)]) {
    def this(l: Seq[T]) = this(l.toList.map(v => (v.toString, v)))
    def this(m: Map[String, T]) = this(m.toList)
    
    def get(arg: String): T = {
      def display(l: List[(String, T)]): String = if (verbose_errors) "    (%s)".format(l.map(_._1).mkString(", ")) else ""
      vals.filter(_._1.startsWith(arg)).sortWith(_._1.length < _._1.length) match {
        case x :: Nil => x._2
        case x :: xs  => if (x._1 == arg) x._2 else throw new AmbiguousArgument(display(x :: xs))
        case Nil => throw new InvalidArgument(display(vals))
      }
    }
  }
  
  protected class ArgSwitchWithVals[T](n: Names, d: Seq[String], vals: ValueList[T], func: T => Unit) extends Switch(n, d) {
    override val takesArg    = true
    override val requiresArg = true
    
    override def process(arg: Option[String]): Unit = arg match {
      case None => throw new RuntimeException("Internal error - no arg for ArgSwitchWithVals")
      case Some(a) => func(vals.get(a))
    }
  }

  protected class OptArgSwitchWithVals[T](n: Names, d: Seq[String], vals: ValueList[T], func: Option[T] => Unit) extends Switch(n, d) {
    override val takesArg = true
    override def process(arg: Option[String]): Unit = func(arg.map(vals.get))
  }

  // Add a new switch to the list.  If any existing switch has the same short or long name
  // as the new switch then it is first removed.  Thus a new switch can potentially replace
  // two existing switches.
  protected def addSwitch(switch: Switch): Unit = {
    def remove(p: Switch => Boolean): Unit = 
      switches.findIndexOf(p) match {
        case -1  =>
        case idx => switches.remove(idx)
      }
    
    if (switch.short != "") remove(_.short == switch.short)
    if (switch.long  != "") remove(_.long  == switch.long)
    switches += switch
  }
  
  private val ShortSpec = """-(\S)(?:\s*(.+))?"""r
  private val LongSpec  = """--([^\s=]+)(?:(=|\[=|\s+)(\S.*))?"""r
  
  // Parse the switch names and return the 'fixed' names.
  // Short name: 
  //   - must begin with a single '-'
  //   - may be followed by an argument name with or without separating space.
  // Long name:
  //   - must begin with two '--'
  //   - may be followed by a argument. If present the argument must be separated from
  //     the long name in one of the following ways:
  //       1.  spaces  #=> --name NAME, --name [NAME]
  //       2.  =       #=> --name=NAME, --name=[NAME]
  //       3.  [=      #=> --name[=NAME]
  //
  // If an arg is specified for both the short and long, then the long one will take
  // precedence.
  // We also the display string for the names.
  protected def getNames(shortSpec: String, longSpec: String): Names = {
    var short = ""
    var long  = ""
    var arg   = ""
    var l_delim = " "
    shortSpec.trim match {
      case "" =>
      case ShortSpec(n, a) if a == null => short = n
      case ShortSpec(n, a) => short = n; arg = a
      case x => throw new OptionParserException("Invalid short name specification: " + x)
    }

    longSpec.trim match {
      case "" =>
      case LongSpec(n, _, a) if a == null => long = n
      case LongSpec(n, d, a) => long = n; l_delim = d.substring(0, 1); arg = a
      case x => throw new OptionParserException("Invalid long name specification: " + x)
    }
    
    val display = (short, long, arg) match {
      case ("", "",  _) => throw new OptionParserException("Both long and short name specifications cannot be blank")
      case (s,  "", "") => "-%s".format(s)
      case ("",  l, "") => "    --%s".format(l)
      case (s,  "",  a) => "-%s %s".format(s, a)
      case (s,   l, "") => "-%s, --%s".format(s, l)
      case ("",  l,  a) => "    --%s%s%s".format(l, l_delim, a)
      case (s,   l,  a) => "-%s, --%s%s%s".format(s, l, l_delim, a)
    }
    Names(short, long, display)
  }
    
  // A list of registered converters
  protected var _converters = List[(ClassManifest[_], String => _)]()
  
  // Look up a converter given a ClassManifest.
  // Throws an exception if not found.
  protected def converter[T](m: ClassManifest[T]): String => T = {
    // Check for exact match first then subclass
    _converters.find(m == _._1).map(_._2.asInstanceOf[String => T]).getOrElse {
      throw new OptionParserException("No Converter found for " + m)
    }
  }
  
  // Look up a switch by the given long name.
  // Partial name lookup is performed. If more than one match is found then if one is an
  // exact match it wins, otherwise and AmbiguousOption exception is thrown
  // Throws InvalidOption if the switch cannot be found
  protected def longSwitch(name: String, arg: Option[String]): SwitchToken = {
    def display(l: List[Switch]): String = if (verbose_errors) "    (%s)".format(l.map("--" + _.long).mkString(", ")) else ""
    val switch = switches.toList.filter(_.long.startsWith(name)).sortWith(_.long.length < _.long.length) match {
      case x :: Nil => x
      case x :: xs  => if (x.long == name) x else throw new AmbiguousOption(display(x :: xs))
      case Nil => throw new InvalidOption
    }
    SwitchToken(switch, true, arg)
  }
  
  // Look up a swith by the given short name.  Must be an exact match.
  protected def shortSwitch(name: String, arg: Option[String]): SwitchToken = {
    val switch = switches.find(_.short == name).getOrElse { throw new InvalidOption }
    SwitchToken(switch, false, arg)
  }
  
  private val TerminationToken   = "--"r
  private val StdinToken         = "(-)"r
  private val LongSwitchWithArg  = "--([^=]+)=(.*)"r
  private val LongSwitch         = "--(.*)"r
  private val ShortSwitch        = "-(.)(.+)?"r
  
  // Get the next token from the argv buffer.
  protected def nextToken: Token = {
    if (argv.isEmpty)
      Terminate()
    else {
      _curr_arg_display = argv(0)
      argv.remove(0) match {
        // The order of the cases here is important!
        case TerminationToken()           => Terminate()
        case StdinToken(arg)              => NonSwitch(arg)
        case LongSwitchWithArg(name, arg) => longSwitch(name, Some(arg))
        case LongSwitch(name)             => longSwitch(name, None)
        case ShortSwitch(name, null)      => shortSwitch(name, None)
        case ShortSwitch(name, arg)       => shortSwitch(name, Some(arg)) 
        case arg                          => NonSwitch(arg)
      }
    }
  }
  
  // ==========================================================================================
  private def errMsg(s: String) = if (verbose_errors) s else ""
  
  // Define default converters
  addConverter {s: String => s}
  addConverter { s: String => 
    try { s.toInt } catch { case _: NumberFormatException => throw new InvalidArgumentException(errMsg("Integer expected")) }
  }
  addConverter { s: String => 
    try { s.toShort } catch { case _: NumberFormatException => throw new InvalidArgumentException(errMsg("Short expected")) }
  }
  addConverter { s: String => 
    try { s.toLong } catch { case _: NumberFormatException => throw new InvalidArgumentException(errMsg("Long expected")) }
  }
  addConverter { s: String => 
    try { s.toFloat } catch { case _: NumberFormatException => throw new InvalidArgumentException(errMsg("Float expected")) }
  }
  addConverter { s: String => 
    try { s.toDouble } catch { case _: NumberFormatException => throw new InvalidArgumentException(errMsg("Double expected")) }
  }
  addConverter { s: String => 
    s.toList match {
      case c :: Nil => c
      case _ => throw new InvalidArgument(errMsg("Char expected"))
    }
  }
  // ==========================================================================================
  
}

object Foo {
  def main(args: Array[String]): Unit = {
    val cli = new OptionParser {
      banner = "usage: Foo [options]"
      separator("")
      separator("Main options:")
      noArg("-x",   "--expert", "Expert Mode") { () => println("Expert Mode")}
      reqArg("-l",  "--length ARG", "Set length") { len: Int => println("Set Length: " +  len)}
      reqArg("-n",  "--name NAME", List("dakota", "mingus", "me"), "Set Name") { s => println("Set Name: " +  s)}
      optArg("-t",  "--type [TYPE]", List("short", "tall", "tiny"), "Set type") { theType: Option[String] => println("Set type: " + theType)}
      separator("")
      separator("Other options:")
      reqArg(""  ,  "--text TEXT", "Set text") { text: String => println("Set text: " + text)}
      reqArg("-a",  "--act NAME", "Set Act") { s: String => println("Set Act: " +  s)}
      optArg("-b",  "--build [NAME]", "Set Build name. Default: 'build'") { theType: Option[String] => println("Set build: " + theType)}
      listArg("-a", "--ages (46,11,...)", "Set ages") { ages: List[Int] => println("Set ages: " + ages)}
    }
    
    try {
      println("Args: " + cli.parse(args))
    }
    catch {
      case e: OptionParserException => println(e.getMessage); exit(1)
    }
  }
}
